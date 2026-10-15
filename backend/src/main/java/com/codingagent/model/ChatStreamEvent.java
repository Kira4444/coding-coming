package com.codingagent.model;

/**
 * 流式响应的事件粒度。
 *
 * <p>刻意拆成「增量」而不是「整段结果」：SSE 要求边到边推，
 * 若等整段再发，用户看到的就是转圈而不是打字机效果。
 */
public sealed interface ChatStreamEvent {

    /** 文本增量 */
    record TextDelta(String text) implements ChatStreamEvent {
    }

    /**
     * 推理模型的思考过程增量。
     *
     * <p><b>只用于界面展示，绝不写进对话历史。</b>
     * 小米 MiMo 文档明确要求：多轮工具调用中模型会返回 {@code reasoning_content}，
     * 但不能把它回填到 messages 里，否则后续轮次会被拒收或行为异常。
     * 我们的 {@link ChatMessage} 根本没有这个字段，从结构上杜绝了误传。
     */
    record ReasoningDelta(String text) implements ChatStreamEvent {
    }

    /**
     * 工具调用增量。同一 {@code index} 的片段需在调用侧拼接成完整的 {@link ToolCall}。
     * {@code name} 通常只在首个片段出现，{@code argumentsDelta} 则是分片的 JSON 串。
     */
    record ToolCallDelta(
            int index,
            String id,
            String name,
            String argumentsDelta
    ) implements ChatStreamEvent {
    }

    /** token 用量；部分兼容实现不下发，允许为空 */
    record Usage(int promptTokens, int completionTokens) implements ChatStreamEvent {
    }

    /** 一轮模型调用正常结束 */
    record Completed() implements ChatStreamEvent {
    }

    /** 模型调用失败（网络、鉴权、限流、上游 5xx） */
    record Error(String message, boolean retryable) implements ChatStreamEvent {
    }
}
