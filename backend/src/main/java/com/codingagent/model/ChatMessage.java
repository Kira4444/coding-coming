package com.codingagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 兼容的对话消息。
 *
 * <p>用同一条记录承载 user / assistant / tool 三种角色，靠 {@code toolCalls} 与
 * {@code toolCallId} 的有无区分，避免为每种角色建一个类导致序列化分支散落。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        Role role,
        String content,

        /** assistant 发起的工具调用列表；仅 role=assistant 时有值 */
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,

        /** tool 消息回填的调用 id；仅 role=tool 时有值 */
        @JsonProperty("tool_call_id") String toolCallId,

        /** tool 消息的名称，便于模型辨识是哪个工具的回执 */
        String name
) {

    public enum Role {
        @JsonProperty("system") SYSTEM,
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT,
        @JsonProperty("tool") TOOL
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        return new ChatMessage(Role.TOOL, content, null, toolCallId, name);
    }

    /**
     * 用于上下文截断时估算 token 占用。不做精确分词，按中文/英文混合的粗略经验值折算：
     * 英文约 4 字符/token，中文约 1.5 字符/token，取中间值 2.5 字符/token。
     */
    public int estimatedTokens() {
        int chars = (content == null ? 0 : content.length());
        if (toolCalls != null) {
            for (ToolCall tc : toolCalls) {
                chars += (tc.name() == null ? 0 : tc.name().length());
                chars += (tc.arguments() == null ? 0 : tc.arguments().length());
            }
        }
        return Math.max(1, (int) Math.ceil(chars / 2.5));
    }
}
