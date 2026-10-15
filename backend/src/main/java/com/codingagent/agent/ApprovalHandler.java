package com.codingagent.agent;

import com.codingagent.model.ToolCall;

/**
 * 审批钩子。agent loop 发起工具调用前先问这里，拿到放行才动手。
 *
 * <p>阻塞式是刻意的：agent 的执行序列必须严格「请求 → 裁决 → 执行」，
 * 异步回调会让上下文里的消息顺序乱掉。
 */
@FunctionalInterface
public interface ApprovalHandler {

    /**
     * @return true = 放行；false = 拒绝（拒绝时工具不执行，把拒绝原因回给模型）
     * @throws InterruptedException 用户未裁决就被中止时抛出
     */
    boolean approve(String approvalId, ToolCall toolCall, String preview) throws InterruptedException;

    /** 全自动放行，用于策略 {@code auto} 或无交互场景（如 CI） */
    ApprovalHandler ALLOW_ALL = (id, call, preview) -> true;
}
