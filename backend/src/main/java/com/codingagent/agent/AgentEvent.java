package com.codingagent.agent;

import com.codingagent.model.ChatMessage;
import com.codingagent.model.ToolCall;

import java.time.Instant;
import java.util.List;

/**
 * agent 执行过程中推给前端的事件。
 *
 * <p>与 {@code ChatStreamEvent} 分开：那是「模型说了什么」，
 * 这是「agent 做了什么」。混在一起前端就没法把工具调用折叠成卡片。
 */
public sealed interface AgentEvent {

    Instant at();

    /** 一轮用户输入开始 */
    record TurnStarted(String sessionId, int turn) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 模型输出的文本增量 */
    record TextDelta(String text) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /**
     * 推理模型的思考过程。
     *
     * <p>仅供展示。这一段<b>不进对话历史</b>——小米 MiMo 等推理模型明确要求
     * 不能把 {@code reasoning_content} 回填到 messages，否则后续轮次异常。
     */
    record ReasoningDelta(String text) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 模型决定调用工具（尚未执行） */
    record ToolCallRequested(ToolCall toolCall) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /**
     * 需要人工确认。前端据此弹审批，用户操作后回调 {@code /api/sessions/{id}/approval}。
     * {@code approvalId} 由 agent loop 生成，用于配对。
     */
    record ApprovalRequired(
            String approvalId,
            ToolCall toolCall,
            String reason,
            String preview
    ) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 用户已放行 */
    record ApprovalResolved(String approvalId, boolean approved) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 工具执行结果 */
    record ToolCallCompleted(ToolCall toolCall, ToolResult result, long elapsedMs) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** token 用量更新 */
    record UsageUpdated(long promptTokens, long completionTokens, long totalTokens) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /**
     * 一条 assistant 消息已被写入对话历史。
     *
     * <p>必须与 tool 结果一起落库，否则会话恢复后会出现「有 tool 回执却没有对应的
     * assistant tool_calls」——OpenAI 兼容层会直接拒收这种消息序列。
     *
     * @param reasoning 推理过程，仅供界面回看。<b>不进对话历史</b>，回传模型时必须丢弃
     */
    record AssistantCommitted(String content, List<ToolCall> toolCalls, String reasoning) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 一轮正常结束 */
    record TurnCompleted(int totalTurns) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }

    /** 出错终止 */
    record Failed(String message) implements AgentEvent {
        public Instant at() {
            return Instant.now();
        }
    }
}
