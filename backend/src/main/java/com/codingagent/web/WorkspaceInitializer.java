package com.codingagent.web;

import com.codingagent.storage.entity.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话工作区初始化：把项目代码弄进 session 的独立目录。
 *
 * <p>直接在宿主机上跑 {@code git}，不走沙箱：这是「给 agent 准备工作区」这一步，
 * 本身就是要操作宿主机目录的，套一层沙箱只是绕路。沙箱留给 agent 执行命令用。
 *
 * <p>三种来源：
 * <ul>
 *   <li>{@code GIT_URL} —— {@code git clone} 远端仓库</li>
 *   <li>{@code LOCAL_PATH} 且是 git 仓库 —— 从本地路径 clone（同样拿到完整历史，且比复制快）</li>
 *   <li>{@code LOCAL_PATH} 非 git —— 复制目录树并 {@code git init} + 首次提交，
 *       这样会话分支与 diff 才有基线</li>
 * </ul>
 */
@Service
public class WorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final int CLONE_TIMEOUT_SECONDS = 600;

    /**
     * 填充工作区。
     *
     * @throws InitException 失败时抛出，消息面向用户，不含堆栈
     */
    public void initialize(Project project, Path workspace, String sessionBranch) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new InitException("创建工作区目录失败: " + e.getMessage(), e);
        }

        // 已有内容就不重复初始化——恢复会话时不应把用户的改动覆盖掉
        if (!isEmptyDir(workspace)) {
            log.info("工作区 {} 已有内容，跳过初始化", workspace);
            ensureBranch(workspace, sessionBranch);
            return;
        }

        String repoType = project.getRepoType();
        if ("GIT_URL".equals(repoType)) {
            cloneFromUrl(project.getRepoUrl(), workspace);
        } else if ("LOCAL_PATH".equals(repoType)) {
            copyFromLocal(project.getLocalPath(), workspace);
        } else {
            throw new InitException("不支持的代码来源类型: " + repoType);
        }

        ensureBranch(workspace, sessionBranch);
        log.info("工作区 {} 初始化完成（{}）", workspace, repoType);
    }

    private void cloneFromUrl(String repoUrl, Path workspace) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new InitException("项目未配置 git 地址");
        }
        // clone 到已存在的空目录要加个点，否则 git 会拒绝
        int code = run(workspace.getParent(), CLONE_TIMEOUT_SECONDS,
                "git", "clone", repoUrl, workspace.toString());
        if (code != 0) {
            throw new InitException("git clone 失败（" + code + "）。"
                    + "请检查地址是否正确、凭据是否可用。私有仓库目前需要服务器上已配好 git 凭据。");
        }
    }

    private void copyFromLocal(String localPath, Path workspace) {
        if (localPath == null || localPath.isBlank()) {
            throw new InitException("项目未配置本地路径");
        }
        Path src = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            throw new InitException("本地路径不存在或不是目录: " + localPath);
        }
        if (src.equals(workspace.toAbsolutePath().normalize())) {
            throw new InitException("工作区不能与来源路径相同");
        }

        boolean srcIsGit = Files.isDirectory(src.resolve(".git"));
        if (srcIsGit) {
            // 从本地 git 仓库 clone：拿到完整历史，比复制树快且省空间
            int code = run(workspace.getParent(), CLONE_TIMEOUT_SECONDS,
                    "git", "clone", src.toString(), workspace.toString());
            if (code != 0) {
                throw new InitException("从本地仓库初始化失败（git clone 返回 " + code + "）");
            }
            return;
        }

        // 非 git 目录：复制后补一个初始提交，让分支与 diff 有基线
        copyTree(src, workspace);
        run(workspace, 60, "git", "init", "-q");
        run(workspace, 60, "git", "add", "-A");
        int code = run(workspace, 60,
                "git", "-c", "user.email=coding-agent@local", "-c", "user.name=coding-agent",
                "commit", "-q", "--allow-empty", "-m", "Initial snapshot");
        if (code != 0) {
            throw new InitException("为工作区创建初始提交失败（" + code + "）");
        }
    }

    /** 切到会话专属分支；无仓库时跳过 */
    private void ensureBranch(Path workspace, String branch) {
        if (branch == null || branch.isBlank()) {
            return;
        }
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            log.warn("工作区 {} 不是 git 仓库，跳过分支创建", workspace);
            return;
        }

        // 必须配提交身份。clone 下来的仓库继承了源仓库配置或宿主机配置，
        // 但新建的（非 git 目录复制）那条路径下 git 会直接拒绝 commit：
        //   error: Author identity unknown
        // 表现为 agent 改完代码却提交不了，用户看到的是一条失败的工具调用。
        // 只写本地仓库配置，不动全局。
        run(workspace, 30, "git", "config", "user.email", "coding-agent@local");
        run(workspace, 30, "git", "config", "user.name", "coding-agent");
        // Windows 上 CRLF 会把 diff 搞脏，统一按 LF 存
        run(workspace, 30, "git", "config", "core.autocrlf", "false");

        int code = run(workspace, 60, "git", "checkout", "-B", branch);
        if (code != 0) {
            // 不致命：没有分支也能改代码，只是 diff 的基线会退化成 HEAD
            log.warn("创建会话分支 {} 失败（{}），继续使用当前分支", branch, code);
        }
    }

    // ---------- 复制 ----------

    private static final List<String> SKIP = List.of(
            ".git", "node_modules", "target", "build", "dist", ".venv", ".idea");

    private void copyTree(Path src, Path dst) {
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(src)) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (SKIP.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    try {
                        Files.createDirectories(dst.resolve(src.relativize(dir)));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (SKIP.contains(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Files.copy(file, dst.resolve(src.relativize(file)),
                                StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw new InitException("复制工作区失败: " + e.getCause().getMessage(), e);
        } catch (IOException e) {
            throw new InitException("复制工作区失败: " + e.getMessage(), e);
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    // ---------- 进程 ----------

    /** 返回退出码；stdout/stderr 记日志 */
    private int run(Path cwd, int timeoutSeconds, String... cmd) {
        List<String> cmdline = List.of(cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdline);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = drain(p.getInputStream(), out);
            Thread tErr = drain(p.getErrorStream(), err);
            tOut.start();
            tErr.start();

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("命令超时: {}", String.join(" ", cmdline));
                return -1;
            }
            tOut.join(2000);
            tErr.join(2000);

            if (p.exitValue() != 0) {
                log.warn("命令失败 {} -> {}\nstdout: {}\nstderr: {}",
                        String.join(" ", cmdline), p.exitValue(), out, err);
            } else {
                log.debug("命令完成 {} -> 0", String.join(" ", cmdline));
            }
            return p.exitValue();
        } catch (IOException e) {
            log.error("命令启动失败 {}: {}", String.join(" ", cmdline), e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sb) {
        return new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 流被强制关闭属预期
            }
        }, "ws-init-drain");
    }

    /**
     * 工作区初始化失败。
     *
     * <p>绝大多数情况是<b>用户配置错了</b>（路径不存在、地址拼错、凭据没配），
     * 属于可自行修正的输入问题，应映射成 400 而不是 500。
     */
    public static class InitException extends RuntimeException {
        public InitException(String message) {
            super(message);
        }

        public InitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
