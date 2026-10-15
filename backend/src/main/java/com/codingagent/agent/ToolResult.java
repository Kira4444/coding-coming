package com.codingagent.agent;

/**
 * 工具执行结果。
 *
 * <p>失败也走正常返回而不是抛异常：模型需要看到失败原因才能自我修正，
 * 异常会让整轮对话中断，用户只能重来。
 */
public record ToolResult(boolean ok, String content, boolean truncated) {

    public static ToolResult success(String content) {
        return new ToolResult(true, content == null ? "" : content, false);
    }

    public static ToolResult success(String content, boolean truncated) {
        return new ToolResult(true, content == null ? "" : content, truncated);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(false, message == null ? "未知错误" : message, false);
    }
}
