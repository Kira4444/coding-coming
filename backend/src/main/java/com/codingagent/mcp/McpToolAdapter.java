package com.codingagent.mcp;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 把 MCP 工具包装成内部 {@link Tool}。
 *
 * <p>对外暴露的工具名带命名空间 {@code mcp__{server}__{tool}}：
 * <ul>
 *   <li>不同服务器可以有同名工具，不会互相覆盖</li>
 *   <li>与内置工具（shell / write_file …）天然不冲突</li>
 *   <li>用户在审批弹窗里一眼能看出调用来自哪个服务器</li>
 * </ul>
 */
public class McpToolAdapter implements Tool {

    public static final String PREFIX = "mcp__";

    private final String serverName;
    private final McpToolSpec spec;
    private final McpClient client;
    private final ObjectMapper mapper;
    private final long timeoutMs;

    public McpToolAdapter(String serverName, McpToolSpec spec, McpClient client,
                          ObjectMapper mapper, long timeoutMs) {
        this.serverName = serverName;
        this.spec = spec;
        this.client = client;
        this.mapper = mapper;
        this.timeoutMs = timeoutMs;
    }

    /** 命名空间化后的工具名 */
    public static String namespaced(String serverName, String toolName) {
        return PREFIX + serverName + "__" + toolName;
    }

    /** 是否为 MCP 工具名 */
    public static boolean isMcpName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    public String serverName() {
        return serverName;
    }

    @Override
    public String name() {
        return namespaced(serverName, spec.name());
    }

    @Override
    public String description() {
        String desc = spec.description() == null ? "" : spec.description();
        return "[" + serverName + "] " + desc;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // MCP 的 inputSchema 本身就是 JSON Schema，直接透传
        return spec.inputSchema() == null
                ? Map.of("type", "object", "properties", Map.of())
                : spec.inputSchema();
    }

    /**
     * 审批判定。
     *
     * <p>MCP 工具来自外部进程，能力完全取决于服务器实现——
     * 无法静态判断只读还是写。一律要求确认，交给会话策略放宽。
     */
    @Override
    public boolean requiresApproval(JsonNode args) {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        if (!client.isAlive()) {
            return ToolResult.failure("MCP 服务器 [" + serverName + "] 已断开，请在项目设置里测试连接");
        }
        try {
            String output = client.callTool(spec.name(), args, timeoutMs);
            return ToolResult.success(output);
        } catch (McpException e) {
            return ToolResult.failure("MCP 工具 [" + name() + "] 调用失败: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("MCP 工具 [" + name() + "] 异常: " + e.getMessage());
        }
    }

    /** 供审计日志打印 */
    public String describe() {
        return "mcp server=" + serverName + " tool=" + spec.name();
    }
}
