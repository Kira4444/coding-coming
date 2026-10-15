package com.codingagent.model;

import java.util.List;
import java.util.Map;

/**
 * 发往模型的一次请求。
 *
 * <p>刻意不直接复用 OpenAI 的请求 JSON 结构：那是 wire format，
 * 领域对象里塞满 {@code stream} / {@code top_p} 之类的开关会让调用方到处透传细节。
 */
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        Double temperature,
        Integer maxOutputTokens
) {

    /** 工具的 JSON Schema 描述，对应 OpenAI 的 {@code tools[].function} */
    public record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private List<ChatMessage> messages;
        private List<ToolSpec> tools;
        private Double temperature = 0.2;
        private Integer maxOutputTokens;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            this.tools = tools;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(model, messages, tools, temperature, maxOutputTokens);
        }
    }
}
