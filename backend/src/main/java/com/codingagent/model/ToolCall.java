package com.codingagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次工具调用。{@code arguments} 是 JSON 字符串，不是对象——
 * OpenAI 兼容接口在流式传输时按字符串增量下发，解析交给工具自己做。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        @JsonProperty("type") String type,
        Function function
) {

    public record Function(String name, String arguments) {
    }

    public static ToolCall of(String id, String name, String arguments) {
        return new ToolCall(id, "function", new Function(name, arguments == null ? "{}" : arguments));
    }

    public String name() {
        return function == null ? null : function.name();
    }

    public String arguments() {
        return function == null ? null : function.arguments();
    }

    public int estimatedTokens() {
        int chars = (name() == null ? 0 : name().length())
                + (arguments() == null ? 0 : arguments().length());
        return Math.max(1, (int) Math.ceil(chars / 2.5));
    }
}
