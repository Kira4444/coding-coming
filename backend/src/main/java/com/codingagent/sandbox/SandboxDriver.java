package com.codingagent.sandbox;

/**
 * 沙箱驱动抽象。
 *
 * <p>两个实现：
 * <ul>
 *   <li>{@link LocalProcessSandboxDriver} —— 本机开发用，直接在工作区目录起进程。
 *       <b>不隔离，仅限开发环境</b></li>
 *   <li>{@code DockerSandboxDriver} —— 一会话一容器，服务器部署用</li>
 * </ul>
 *
 * <p>把接口立在这是刻意的：本机没装 Docker 也能开发调试 agent loop，
 * 生产换驱动不改工具层与 agent 层一行代码。
 */
public interface SandboxDriver {

    /** 创建并启动一个沙箱会话，失败抛 {@link SandboxException} */
    SandboxSession create(CreateRequest request);

    record CreateRequest(
            String sessionId,
            /** 工作区在宿主机上的落盘路径 */
            String hostWorkspacePath,
            /** 自定义镜像；为空则用默认镜像。local 驱动忽略此项 */
            String image,
            /** 网络策略：public / none / custom */
            String network,
            int cpu,
            String memory
    ) {
        public static CreateRequest of(String sessionId, String hostWorkspacePath) {
            return new CreateRequest(sessionId, hostWorkspacePath, null, "public", 2, "4g");
        }
    }

    class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }

        public SandboxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
