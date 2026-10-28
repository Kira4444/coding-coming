package com.codingagent.agent;

import com.codingagent.sandbox.SandboxSession;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个会话的运行期状态。
 *
 * <p>与 DB 里的 session 记录分开：DB 是持久事实，这里是「这个会话此刻活着」的现场——
 * 沙箱句柄、待裁决的审批、正在跑的标记。进程重启后这些都消失，
 * DB 记录仍在，会话可重新激活（工作区没被回收的话）。
 */
public class SessionRuntime {

    private final String sessionId;
    private final SandboxSession sandbox;
    private final AgentLoop.SessionPolicy policy;
    private final Instant startedAt = Instant.now();

    /** 正在等待裁决的审批：approvalId -> 未来结果 */
    private final Map<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * 本轮是否正在执行，防止并发触发两次。
     *
     * <p><b>这个实例不是本类创建的</b>，由调用方注入（见 SessionService 的 runGates）。
     * 原因：闸门必须与「runtime 是否存在」无关——runtime 要等 {@code activate()}
     * 才建出来，而回滚/恢复要能与<b>尚未激活的会话</b>互斥。把闸门内嵌在这里的话，
     * 重启后第一次操作两边拿到的是不同的对象（一边压根没有），互斥就失效了。
     */
    private final AtomicBoolean running;

    public SessionRuntime(String sessionId, SandboxSession sandbox, AgentLoop.SessionPolicy policy,
                          AtomicBoolean running) {
        this.sessionId = sessionId;
        this.sandbox = sandbox;
        this.policy = policy;
        this.running = running;
    }

    public String sessionId() {
        return sessionId;
    }

    public SandboxSession sandbox() {
        return sandbox;
    }

    public AgentLoop.SessionPolicy policy() {
        return policy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public AtomicBoolean running() {
        return running;
    }

    /** 注册一个待裁决的审批，返回它的未来 */
    public CompletableFuture<Boolean> registerApproval(String approvalId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);
        return future;
    }

    /** 用户裁决了某个审批 */
    public boolean resolveApproval(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.remove(approvalId);
        if (future == null) {
            return false;
        }
        return future.complete(approved);
    }

    public int pendingApprovalCount() {
        return pendingApprovals.size();
    }

    /** 裁决不了就全拒，避免 agent 永久挂起 */
    public void failAllApprovals() {
        pendingApprovals.forEach((id, f) -> f.complete(false));
        pendingApprovals.clear();
    }

    public void close() {
        failAllApprovals();
        try {
            sandbox.close();
        } catch (Exception ignored) {
            // 关闭失败不影响会话记录
        }
    }
}
