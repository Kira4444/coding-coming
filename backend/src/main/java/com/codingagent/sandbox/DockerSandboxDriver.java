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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker 实现的沙箱：一会话一容器。
 *
 * <p>用 {@code docker} CLI 而不是 docker-java SDK：依赖面更小，
 * 且宿主机上有什么版本的 Docker 都能用。代价是要自己解析输出，
 * 但我们的交互面很窄（create / exec / rm），可控。
 *
 * <p><b>网络策略</b>：{@code public} 用默认 bridge（可出公网）；
 * {@code none} 用 {@code --network none}（完全断网）。
 * 「禁内网」由宿主机的 Docker 网络配置决定，这里只做能力开关——
 * 部署文档里必须写清楚这一点，否则会出现「以为禁了其实没禁」。
 */
@Component
@ConditionalOnProperty(name = "coding-agent.sandbox.driver", havingValue = "docker")
public class DockerSandboxDriver implements SandboxDriver {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxDriver.class);
    private static final String LABEL = "coding-agent.session";

    @Override
    public SandboxSession create(CreateRequest request) {
        requireDockerAvailable();

        String containerName = "coding-agent-" + request.sessionId();
        String hostPath = Path.of(request.hostWorkspacePath()).toAbsolutePath().normalize().toString();

        try {
            Files.createDirectories(Path.of(hostPath));
        } catch (IOException e) {
            throw new SandboxException("创建工作区目录失败: " + hostPath, e);
        }

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "-d",
                "--name", containerName,
                "--label", LABEL + "=" + request.sessionId(),
                // 资源限额：没限额的 agent 容器能拖垮整台宿主机
                "--cpus", String.valueOf(request.cpu()),
                "--memory", request.memory(),
                "--memory-swap", request.memory(),
                // 禁止提权；容器内已是 agent 用户
                "--security-opt", "no-new-privileges",
                // 工作区 bind mount 进去，agent 的改动直接落在宿主机数据卷上
                "-v", hostPath + ":/workspace",
                "-w", "/workspace"));

        if ("none".equalsIgnoreCase(request.network())) {
            cmd.add("--network");
            cmd.add("none");
        }

        String image = request.image() != null && !request.image().isBlank()
                ? request.image()
                : "coding-agent/sandbox:latest";
        cmd.add(image);

        String containerId = runForOutput(cmd).trim();
        if (containerId.isEmpty()) {
            throw new SandboxException("创建沙箱容器失败，镜像可能存在: " + image
                    + "。请先执行 docker build -t " + image + " sandbox/");
        }

        log.info("[{}] 已创建沙箱容器 {} image={} network={} cpus={} mem={}",
                request.sessionId(), containerId.substring(0, Math.min(12, containerId.length())),
                image, request.network(), request.cpu(), request.memory());

        return new DockerSession(request.sessionId(), containerName, hostPath, request);
    }

    private void requireDockerAvailable() {
        try {
            runForOutput(List.of("docker", "version", "--format", "{{.Server.Version}}"));
        } catch (Exception e) {
            throw new SandboxException(
                    "Docker 不可用。沙箱驱动为 docker 时宿主机必须安装 Docker 并对服务用户开放权限。"
                            + "开发环境可改用 coding-agent.sandbox.driver=local", e);
        }
    }

    /** 执行命令并返回 stdout，非零退出码抛异常 */
    private String runForOutput(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out;
            String err;
            try (BufferedReader o = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                out = readAll(o);
                err = readAll(e);
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new SandboxException("命令超时: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new SandboxException("命令失败 (" + p.exitValue() + "): "
                        + String.join(" ", cmd) + "\n" + err);
            }
            return out;
        } catch (IOException e) {
            throw new SandboxException("无法执行 docker 命令: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("命令被中断", e);
        }
    }

    private static String readAll(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static final class DockerSession implements SandboxSession {

        private final String id;
        private final String containerName;
        private final String hostWorkspacePath;
        private final CreateRequest request;
        private final Instant startedAt = Instant.now();

        DockerSession(String id, String containerName, String hostWorkspacePath, CreateRequest request) {
            this.id = id;
            this.containerName = containerName;
            this.hostWorkspacePath = hostWorkspacePath;
            this.request = request;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String workspacePath() {
            // 文件工具跑在宿主机进程里，必须给宿主机路径。
            // 工作区是 bind mount（hostPath:/workspace），两边是同一个目录，
            // 所以工具读写 hostPath 与容器内 /workspace 看到的是同一份内容。
            return hostWorkspacePath;
        }

        @Override
        public Instant startedAt() {
            return startedAt;
        }

        @Override
        public Map<String, String> describe() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("driver", "docker");
            m.put("container", containerName);
            m.put("image", request.image() != null ? request.image() : "coding-agent/sandbox:latest");
            m.put("network", request.network());
            m.put("hostWorkspace", hostWorkspacePath);
            m.put("cpu", String.valueOf(request.cpu()));
            m.put("memory", request.memory());
            return m;
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) {
            List<String> cmd = new ArrayList<>(List.of(
                    "docker", "exec",
                    // 沙箱内默认切到 agent 用户，不给 root
                    "--user", "agent",
                    "-w", "/workspace",
                    containerName,
                    "/bin/sh", "-c", command));

            try {
                Process p = new ProcessBuilder(cmd).start();
                StreamCollector out = new StreamCollector(p.getInputStream());
                StreamCollector err = new StreamCollector(p.getErrorStream());
                Thread tOut = new Thread(out, "docker-out-" + id);
                Thread tErr = new Thread(err, "docker-err-" + id);
                tOut.start();
                tErr.start();

                boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    tOut.join(1000);
                    tErr.join(1000);
                    return new ExecResult(-1, out.text(), err.text() + "\n[超时被终止]", true, false);
                }
                tOut.join(2000);
                tErr.join(2000);
                return new ExecResult(p.exitValue(), out.text(), err.text(), false,
                        out.truncated() || err.truncated());
            } catch (IOException e) {
                return new ExecResult(-1, "", "docker exec 失败: " + e.getMessage(), false, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ExecResult(-1, "", "命令被中断", false, false);
            }
        }

        @Override
        public void close() {
            try {
                Process p = new ProcessBuilder("docker", "rm", "-f", containerName).start();
                p.waitFor(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[{}] 移除沙箱容器失败: {}", id, e.getMessage());
            }
        }
    }

    /** 与 local 驱动共用的输出截断器 */
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
                // 流被强制关闭属预期路径
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
