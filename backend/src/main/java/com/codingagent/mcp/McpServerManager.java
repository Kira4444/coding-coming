package com.codingagent.mcp;

import com.codingagent.storage.entity.McpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 连接管理。
 *
 * <p>一个会话对应一组连接，会话结束时统一关闭。
 * 刻意不做跨会话的连接池复用：MCP 服务器是有状态的（它自己缓存的东西、
 * 打开的文件句柄），复用会让「这个会话看到了上个会话的残留」变成常见投诉。
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final long TOOL_TIMEOUT_MS = 60_000;

    private final ObjectMapper mapper;

    public McpServerManager(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 一个项目在某次执行期间持有的全部 MCP 连接 */
    public static final class Session implements AutoCloseable {

        private final Map<String, McpClient> clients = new LinkedHashMap<>();

        List<McpClient> clients() {
            return List.copyOf(clients.values());
        }

        McpClient client(String serverName) {
            return clients.get(serverName);
        }

        void add(String serverName, McpClient client) {
            clients.put(serverName, client);
        }

        boolean isEmpty() {
            return clients.isEmpty();
        }

        @Override
        public void close() {
            for (McpClient c : clients.values()) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("关闭 MCP 连接失败: {}", e.getMessage());
                }
            }
            clients.clear();
        }
    }

    /**
     * 为一批 MCP 服务器配置建立连接。
     *
     * <p><b>单个服务器连不上不影响其余</b>：一个坏掉的 MCP 不该让整个会话开不起来。
     * 失败的会被跳过并在结果里标注。
     */
    public Session connectAll(List<McpServer> servers) {
        Session session = new Session();
        for (McpServer s : servers) {
            if (s.getEnabled() != null && !s.getEnabled()) {
                continue;
            }
            if (!"STDIO".equalsIgnoreCase(s.getTransport())) {
                log.warn("[mcp:{}] 暂不支持传输方式 {}，已跳过（当前仅支持 STDIO）",
                        s.getName(), s.getTransport());
                continue;
            }
            try {
                McpClient client = new McpClient(s.getName(), mapper);
                client.start(s.getCommand(), parseArgs(s.getArgs()), parseEnv(s.getEnv()));
                session.add(s.getName(), client);
            } catch (Exception e) {
                log.warn("[mcp:{}] 连接失败，已跳过: {}", s.getName(), e.getMessage());
            }
        }
        return session;
    }

    /** 取一个项目的全部 MCP 工具（已加命名空间） */
    public List<McpToolAdapter> toolsOf(Session session) {
        List<McpToolAdapter> tools = new ArrayList<>();
        for (McpClient client : session.clients()) {
            try {
                for (McpToolSpec spec : client.listTools()) {
                    tools.add(new McpToolAdapter(
                            client.serverName(), spec, client, mapper, TOOL_TIMEOUT_MS));
                }
            } catch (Exception e) {
                log.warn("列出 MCP 工具失败: {}", e.getMessage());
            }
        }
        return tools;
    }

    /**
     * 连通性探测：起一次连接、拉一次工具列表、立刻关掉。
     * 结果写回配置行，便于 UI 直接显示。
     */
    public CheckResult check(McpServer server) {
        long start = System.currentTimeMillis();
        McpClient client = new McpClient(server.getName(), mapper);
        try {
            client.start(server.getCommand(), parseArgs(server.getArgs()), parseEnv(server.getEnv()));
            List<McpToolSpec> tools = client.listTools();
            long ms = System.currentTimeMillis() - start;
            return new CheckResult(true, "连接成功，发现 " + tools.size() + " 个工具（" + ms + "ms）",
                    Instant.now(), List.of());
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new CheckResult(false, truncate(msg, 500), Instant.now(), List.of());
        } finally {
            client.close();
        }
    }

    public record CheckResult(boolean ok, String message, Instant at, List<String> tools) {
    }

    // ---------- 配置解析 ----------

    /** 参数：支持 JSON 数组（推荐）与按行分隔两种写法 */
    static List<String> parseArgs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String t = raw.trim();
        if (t.startsWith("[")) {
            try {
                JsonNode arr = new ObjectMapper().readTree(t);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        out.add(n.asText());
                    }
                    return out;
                }
            } catch (Exception ignored) {
                // JSON 解析失败就退回按行解析
            }
        }
        for (String line : t.split("[\\r\\n]+")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 环境变量：KEY=VALUE 每行一条 */
    static Map<String, String> parseEnv(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq > 0) {
                out.put(s.substring(0, eq).trim(), s.substring(eq + 1));
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
