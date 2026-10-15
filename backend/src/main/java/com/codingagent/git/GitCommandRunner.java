package com.codingagent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在宿主机上跑 git。
 *
 * <p>不走沙箱：发布是「把工作区的改动推到远端」这一步，本来就要读宿主机目录、
 * 用宿主机网络。沙箱留给 agent 执行命令用。
 */
@Service
public class GitCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);

    public record GitResult(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isBlank()) {
                sb.append(stdout);
            }
            if (stderr != null && !stderr.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

    /**
     * 执行 git 命令。
     *
     * @param extraEnv 注入的环境变量（凭据走这里，不进命令行）
     * @param args     git 子命令及参数，第一项通常是 git 的子命令
     */
    public GitResult run(Path cwd, Map<String, String> extraEnv, int timeoutSeconds, String... args) {
        List<String> cmdline = new ArrayList<>();
        cmdline.add("git");
        cmdline.addAll(List.of(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdline);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(false);
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }
            // 绝不提示输入凭据：卡在交互提示上比直接失败更难排查
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");

            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = drain(p.getInputStream(), out);
            Thread tErr = drain(p.getErrorStream(), err);
            tOut.start();
            tErr.start();

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GitResult(-1, out.toString(),
                        err + "\n[命令超时 " + timeoutSeconds + "s]");
            }
            tOut.join(2000);
            tErr.join(2000);

            GitResult r = new GitResult(p.exitValue(), out.toString(), err.toString());
            if (!r.ok()) {
                log.warn("git {} 失败 ({}): {}", String.join(" ", args), r.exitCode(), r.combined());
            }
            return r;
        } catch (IOException e) {
            return new GitResult(-1, "", "无法执行 git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "", "命令被中断");
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
        }, "git-drain");
    }
}
