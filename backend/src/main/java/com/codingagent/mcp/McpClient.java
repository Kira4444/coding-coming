package com.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP stdio 客户端。
 *
 * <p>实现的是 MCP 的最小可用子集：{@code initialize} 握手、{@code tools/list}、
 * {@code tools/call}。传输层是<b>换行分隔的 JSON-RPC 2.0</b>——
 * 一行一条消息，写 stdin、读 stdout。
 *
 * <p>刻意不引第三方 MCP SDK：我们的交互面很窄（三个方法），
 * 自己写反而比引入一个大依赖更容易控制超时与失败语义。
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    /** 协议版本。不写死成常量最新版——服务器可能只认某几个版本。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final String serverName;
    private final ObjectMapper mapper;

    private Process process;
    private BufferedWriter stdin;
    private Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** 服务器宣告的能力，便于上层判断它支不支持某类功能 */
    private JsonNode serverCapabilities;
    private String serverVersion;

    public McpClient(String serverName, ObjectMapper mapper) {
        this.serverName = serverName;
        this.mapper = mapper;
    }

    // ---------- 生命周期 ----------

    /**
     * 启动子进程并完成握手。
     *
     * @param command 可执行文件
     * @param args    参数列表
     * @param env     额外环境变量（继承当前进程环境）
     */
    public synchronized void start(String command, List<String> args, Map<String, String> env)
            throws IOException {
        if (process != null) {
            throw new McpException("MCP 客户端 [" + serverName + "] 已启动");
        }
        if (command == null || command.isBlank()) {
            throw new McpException("MCP 服务器 [" + serverName + "] 未配置启动命令");
        }

        List<String> cmdline = new ArrayList<>();
        cmdline.add(command.trim());
        if (args != null) {
            cmdline.addAll(args);
        }

        ProcessBuilder pb = new ProcessBuilder(cmdline);
        pb.redirectErrorStream(false);
        if (env != null) {
            pb.environment().putAll(env);
        }

        log.info("[mcp:{}] 启动 {}", serverName, String.join(" ", cmdline));
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        readerThread = new Thread(this::readLoop, "mcp-reader-" + serverName);
        readerThread.setDaemon(true);
        readerThread.start();

        // 握手
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "coding-agent");
        clientInfo.put("version", "0.1.0");

        JsonNode initResult = request("initialize", params, DEFAULT_TIMEOUT_MS);
        serverCapabilities = initResult.path("capabilities");
        serverVersion = initResult.path("serverInfo").path("version").asText(null);

        notify("notifications/initialized", null);
        log.info("[mcp:{}] 握手完成 version={} capabilities={}", serverName, serverVersion, serverCapabilities);
    }

    /** 列出服务器提供的工具 */
    public List<McpToolSpec> listTools() {
        JsonNode result = request("tools/list", mapper.createObjectNode(), DEFAULT_TIMEOUT_MS);
        List<McpToolSpec> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String name = t.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                JsonNode schema = t.path("inputSchema");
                Map<String, Object> schemaMap = schema.isMissingNode() || schema.isNull()
                        ? Map.of("type", "object", "properties", Map.of())
                        : mapper.convertValue(schema, Map.class);
                tools.add(new McpToolSpec(
                        name,
                        t.path("description").asText(""),
                        schemaMap));
            }
        }
        return tools;
    }

    /**
     * 调用工具，返回拼接后的文本结果。
     *
     * <p>MCP 的结果是 {@code content} 数组，元素可以是 text / image / resource。
     * 这里只处理 text：图片等非文本内容对当前 agent 无意义，丢掉并注明，
     * 而不是把 base64 灌进上下文把预算吃穿。
     */
    public String callTool(String toolName, JsonNode arguments, long timeoutMs) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null || arguments.isNull()
                ? mapper.createObjectNode() : arguments);

        JsonNode result = request("tools/call", params, timeoutMs);

        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            return "(工具无输出)";
        }
        StringBuilder sb = new StringBuilder();
        int skipped = 0;
        for (JsonNode item : content) {
            String type = item.path("type").asText("text");
            if ("text".equals(type)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(item.path("text").asText(""));
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            sb.append("\n[已忽略 ").append(skipped).append(" 项非文本内容]");
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpException("MCP 工具 [" + toolName + "] 返回错误: " + sb);
        }
        return sb.toString();
    }

    public String serverVersion() {
        return serverVersion;
    }

    /** 配置里的服务器标识，用于工具命名空间 */
    public String serverName() {
        return serverName;
    }

    // ---------- JSON-RPC ----------

    private JsonNode request(String method, JsonNode params, long timeoutMs) {
        if (closed.get() || process == null) {
            throw new McpException("MCP 客户端 [" + serverName + "] 未连接");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }

        try {
            write(msg);
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                JsonNode err = response.get("error");
                throw new McpException(
                        "MCP 调用失败 [" + method + "]: " + err.path("message").asText(err.toString()),
                        err.path("code").isInt() ? err.path("code").asInt() : null);
            }
            return response.path("result");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new McpException("MCP 调用超时 [" + method + "] " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP 调用被中断 [" + method + "]", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new McpException("MCP 调用失败 [" + method + "]: " + e.getCause().getMessage(), e);
        } catch (IOException e) {
            throw new McpException("MCP 写入失败 [" + method + "]: " + e.getMessage(), e);
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonNode params) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        try {
            write(msg);
        } catch (IOException e) {
            throw new McpException("MCP 通知发送失败 [" + method + "]", e);
        }
    }

    private synchronized void write(JsonNode msg) throws IOException {
        if (stdin == null) {
            throw new IOException("stdin 未就绪");
        }
        stdin.write(mapper.writeValueAsString(msg));
        stdin.write('\n');
        stdin.flush();
    }

    /**
     * 读 stdout。
     *
     * <p>一行一条消息。服务器偶发输出非 JSON 行（调试打印）时忽略而不是挂掉——
     * 真实世界的 MCP 服务器并不都干净。
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode msg;
                try {
                    msg = mapper.readTree(line);
                } catch (Exception e) {
                    log.debug("[mcp:{}] 忽略非 JSON 输出: {}", serverName,
                            line.length() > 200 ? line.substring(0, 200) + "…" : line);
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("[mcp:{}] 读取中断: {}", serverName, e.getMessage());
            }
        } finally {
            // 读流结束意味着进程退出或管道断开，把所有挂起的请求判失败，
            // 否则调用方会一直等到超时
            failAllPending("MCP 服务器 [" + serverName + "] 连接已断开");
        }
    }

    private void dispatch(JsonNode msg) {
        JsonNode idNode = msg.get("id");
        if (idNode == null || idNode.isNull()) {
            // 通知或服务器主动消息，当前子集用不到，记一笔即可
            log.debug("[mcp:{}] 收到通知: {}", serverName, msg.path("method").asText("?"));
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            log.debug("[mcp:{}] 收到未知 id={} 的响应", serverName, id);
            return;
        }
        future.complete(msg);
    }

    private void failAllPending(String reason) {
        pending.forEach((id, f) -> f.completeExceptionally(new McpException(reason)));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAllPending("MCP 客户端已关闭");
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 关闭失败不影响后续清理
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        log.info("[mcp:{}] 已关闭", serverName);
    }

    public boolean isAlive() {
        return !closed.get() && process != null && process.isAlive();
    }
}
