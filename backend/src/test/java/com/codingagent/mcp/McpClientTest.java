package com.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 客户端的集成测试。
 *
 * <p>协议层的错误（握手字段、请求 id 关联、行分隔）在生产里是<b>静默</b>失败——
 * 连上但调不通、调通但拿错结果。所以这里起一个假 MCP 服务器真跑一遍，
 * 而不是只测参数解析。
 */
class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用 node 起假服务器；没有 node 就跳过整个测试类 */
    static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "-v").start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 一个最小 MCP stdio 服务器：应答 initialize、tools/list、tools/call。
     * 写成临时 JS 文件，因为 {@code node -e} 在 Windows 上的引号转义太脆。
     */
    private Path mockServer(String extraToolsJson) throws IOException {
        Path js = Files.createTempFile("mock-mcp-", ".js");
        js.toFile().deleteOnExit();
        String script = """
                const rl = require('readline').createInterface({ input: process.stdin });
                const tools = [{ name: 'echo', description: '回显输入',
                                 inputSchema: { type: 'object', properties: { text: { type: 'string' } },
                                                required: ['text'] } }]
                  .concat(EXTRA);
                rl.on('line', (line) => {
                  if (!line.trim()) return;
                  let msg;
                  try { msg = JSON.parse(line); } catch (e) { return; }
                  if (msg.id === undefined || msg.id === null) return;
                  let result;
                  switch (msg.method) {
                    case 'initialize':
                      result = { protocolVersion: '2024-11-05',
                                 capabilities: { tools: {} },
                                 serverInfo: { name: 'mock', version: '1.0.0' } };
                      break;
                    case 'tools/list':
                      result = { tools };
                      break;
                    case 'tools/call': {
                      const a = msg.params && msg.params.arguments || {};
                      if (msg.params.name === 'echo') {
                        result = { content: [{ type: 'text', text: 'echo:' + (a.text || '') }] };
                      } else if (msg.params.name === 'boom') {
                        result = { content: [{ type: 'text', text: 'kaboom' }], isError: true };
                      } else {
                        result = { content: [{ type: 'text', text: 'unknown tool' }], isError: true };
                      }
                      break;
                    }
                    default:
                      process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: msg.id,
                        error: { code: -32601, message: 'Method not found' } }) + '\\n');
                      return;
                  }
                  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }) + '\\n');
                });
                """.replace("EXTRA", extraToolsJson);
        Files.writeString(js, script);
        return js;
    }

    @Test
    @EnabledIf("nodeAvailable")
    void 握手与工具发现() throws Exception {
        Path js = mockServer("[]");
        try (McpClient client = new McpClient("mock", MAPPER)) {
            client.start("node", List.of(js.toString()), Map.of());
            assertTrue(client.isAlive());

            List<McpToolSpec> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).name());
            assertNotNull(tools.get(0).inputSchema());
            assertTrue(client.serverName().equals("mock"));
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void 调用工具并取回文本结果() throws Exception {
        Path js = mockServer("[]");
        try (McpClient client = new McpClient("mock", MAPPER)) {
            client.start("node", List.of(js.toString()), Map.of());

            ObjectNode args = MAPPER.createObjectNode();
            args.put("text", "hello");
            String out = client.callTool("echo", args, 5000);
            assertEquals("echo:hello", out);
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void 工具返回isError时抛异常() throws Exception {
        Path js = mockServer("[{ name: 'boom', description: '必炸', inputSchema: { type: 'object' } }]");
        try (McpClient client = new McpClient("mock", MAPPER)) {
            client.start("node", List.of(js.toString()), Map.of());

            List<McpToolSpec> tools = client.listTools();
            assertEquals(2, tools.size());

            ObjectNode args = MAPPER.createObjectNode();
            McpException e = assertThrows(McpException.class,
                    () -> client.callTool("boom", args, 5000));
            assertTrue(e.getMessage().contains("kaboom"), () -> e.getMessage());
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void 关闭后不可再调用() throws Exception {
        Path js = mockServer("[]");
        McpClient client = new McpClient("mock", MAPPER);
        client.start("node", List.of(js.toString()), Map.of());
        client.close();
        assertFalse(client.isAlive());
        assertThrows(McpException.class, () -> client.callTool("echo", MAPPER.createObjectNode(), 1000));
    }

    @Test
    void 命名空间不与内置工具冲突() {
        String name = McpToolAdapter.namespaced("fs", "read_file");
        assertEquals("mcp__fs__read_file", name);
        assertTrue(McpToolAdapter.isMcpName(name));
        assertFalse(McpToolAdapter.isMcpName("read_file"));
        // 内置的 read_file 与 mcp 的 read_file 是两个不同工具
        assertNotEquals("read_file", name);
    }

    @Test
    void 参数解析支持JSON数组与按行分隔() {
        assertEquals(List.of("--port", "8080"), McpServerManager.parseArgs("[\"--port\",\"8080\"]"));
        assertEquals(List.of("--port", "8080"), McpServerManager.parseArgs("--port\n8080"));
        assertTrue(McpServerManager.parseArgs(null).isEmpty());
        assertTrue(McpServerManager.parseArgs("  ").isEmpty());
        // 非法 JSON 退回按行解析，而不是抛异常
        assertEquals(List.of("[oops"), McpServerManager.parseArgs("[oops"));
    }

    @Test
    void 环境变量解析忽略注释与空行() {
        Map<String, String> env = McpServerManager.parseEnv("""
                # 这是注释
                API_KEY=abc123

                TOKEN=xyz
                BROKEN_LINE
                WITH_EQ=a=b=c
                """);
        // BROKEN_LINE 没有等号被跳过；WITH_EQ 的值里含等号，整体作为值保留
        assertEquals(3, env.size());
        assertEquals("abc123", env.get("API_KEY"));
        assertEquals("xyz", env.get("TOKEN"));
        assertEquals("a=b=c", env.get("WITH_EQ"));
        assertFalse(env.containsKey("BROKEN_LINE"));
    }

    @Test
    void 值中含等号只切第一个() {
        Map<String, String> env = McpServerManager.parseEnv("DSN=host=db;user=x=y");
        assertEquals("host=db;user=x=y", env.get("DSN"));
    }
}
