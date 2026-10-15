package com.codingagent.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机进程实现的沙箱。
 *
 * <p><b>安全警告</b>：此实现<b>没有任何隔离</b>，agent 发出的命令直接以服务进程的身份跑在宿主机上。
 * 仅用于本机开发调试；服务器部署必须切到 docker 驱动（{@code coding-agent.sandbox.driver=docker}）。
 */
@Component
@ConditionalOnProperty(name = "coding-agent.sandbox.driver", havingValue = "local", matchIfMissing = true)
public class LocalProcessSandboxDriver implements SandboxDriver {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessSandboxDriver.class);

    @Override
    public SandboxSession create(CreateRequest request) {
        Path root = Path.of(request.hostWorkspacePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new SandboxException("创建工作区目录失败: " + root, e);
        }
        log.warn("local 沙箱 [{}] 工作区 {} —— 命令将以服务进程身份直接执行，仅限开发环境",
                request.sessionId(), root);
        return new LocalSession(request.sessionId(), root);
    }

    static final class LocalSession implements SandboxSession {

        private final String id;
        private final Path root;
        private final Instant startedAt = Instant.now();

        LocalSession(String id, Path root) {
            this.id = id;
            this.root = root;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String workspacePath() {
            return root.toString();
        }

        @Override
        public Instant startedAt() {
            return startedAt;
        }

        @Override
        public Map<String, String> describe() {
            return Map.of(
                    "driver", "local",
                    "sessionId", id,
                    "workspace", root.toString(),
                    "warning", "无隔离，仅限开发环境");
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) {
            List<String> cmdline = shellInvocation(command);
            ProcessBuilder pb = new ProcessBuilder(cmdline);
            pb.directory(root.toFile());
            pb.redirectErrorStream(false);
            // 明确传一个干净的环境，避免把宿主机密钥透进 agent 的命令里
            pb.environment().put("HOME", root.toString());
            pb.environment().put("CI", "true");

            try {
                Process process = pb.start();
                StreamCollector out = new StreamCollector(process.getInputStream());
                StreamCollector err = new StreamCollector(process.getErrorStream());
                Thread tOut = new Thread(out, "sb-out-" + id);
                Thread tErr = new Thread(err, "sb-err-" + id);
                tOut.start();
                tErr.start();

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    tOut.join(1000);
                    tErr.join(1000);
                    return new ExecResult(-1, out.text(), err.text() + "\n[超时被终止]", true, false);
                }
                tOut.join(2000);
                tErr.join(2000);
                return new ExecResult(process.exitValue(), out.text(), err.text(), false,
                        out.truncated() || err.truncated());
            } catch (IOException e) {
                return new ExecResult(-1, "", "命令启动失败: " + e.getMessage(), false, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ExecResult(-1, "", "命令被中断", false, false);
            }
        }

        /**
         * Windows 上用 cmd /c，其余走 sh -c。
         * 这样 agent 产出的命令行不需要为平台分叉。
         */
        private static List<String> shellInvocation(String command) {
            List<String> cmd = new ArrayList<>(3);
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            } else {
                cmd.add("/bin/sh");
                cmd.add("-c");
            }
            cmd.add(command);
            return cmd;
        }

        @Override
        public void close() {
            // local 实现无进程池需要回收；工作区目录保留，由 workspace 生命周期策略负责
        }
    }

    /** 收集子进程输出并做行数/字符数双上限截断 */
    static final class StreamCollector implements Runnable {

        private static final int MAX_LINES = 2000;
        private static final int MAX_CHARS = 200_000;

        private final java.io.InputStream in;
        private final StringBuilder sb = new StringBuilder();
        private volatile boolean truncated;

        StreamCollector(java.io.InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null) {
                    if (lines >= MAX_LINES || sb.length() >= MAX_CHARS) {
                        truncated = true;
                        break;
                    }
                    sb.append(line).append('\n');
                    lines++;
                }
            } catch (IOException ignored) {
                // 流被强制关闭（超时 destroyForcibly）属预期路径
            }
        }

        String text() {
            if (truncated && sb.length() > 0) {
                return sb + "\n…[输出过长已截断]";
            }
            return sb.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
