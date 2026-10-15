package com.codingagent.mcp;

import java.util.Map;

/** MCP 服务器暴露的一个工具。 */
public record McpToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
}
