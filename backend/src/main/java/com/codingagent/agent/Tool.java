package com.codingagent.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * agent 可调用的一个工具。
 *
 * <p>工具自己声明参数 schema 与审批级别，而不是让 agent loop 去 switch 工具名——
 * 新增工具时审批规则跟着工具走，不会漏配。
 */
public interface Tool {

    String name();

    String description();

    /** OpenAI function-calling 的 parameters JSON Schema */
    Map<String, Object> parametersSchema();

    /**
     * 该次调用是否需要人工确认。
     *
     * <p>判定依赖具体入参：同样是 shell 工具，{@code ls} 不必拦，{@code rm -rf} 必须拦。
     * session 级策略为 {@code auto} 时忽略此返回值；为 {@code always-ask} 时恒拦。
     */
    boolean requiresApproval(JsonNode args);

    ToolResult execute(ToolContext ctx, JsonNode args) throws Exception;
}
