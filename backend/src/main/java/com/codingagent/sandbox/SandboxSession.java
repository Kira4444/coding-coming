package com.codingagent.sandbox;

import java.time.Instant;
import java.util.Map;

/**
 * 一个已就绪的执行环境：工作区目录已挂好，可以跑命令。
 *
 * <p>local 实现下它就是宿主机上的一个目录；docker 实现下它是一个活着的容器。
 * 工具层只依赖这个接口，不关心底下是哪种。
 */
public interface SandboxSession extends AutoCloseable {

    String id();

    /** 工作区根路径。local 下是真实路径，docker 下是容器内路径 */
    String workspacePath();

    /**
     * 同步执行一条 shell 命令。
     *
     * @param command shell 命令行
     * @param timeoutSeconds 超时秒数，超时返回 {@code ok=false}
     */
    ExecResult exec(String command, int timeoutSeconds);

    /** 沙箱环境信息，用于审计与调试 */
    Map<String, String> describe();

    Instant startedAt();

    @Override
    void close();

    record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {
        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (stderr != null && !stderr.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("--- stderr ---\n").append(stderr);
            }
            return sb.toString();
        }
    }
}
