package com.codingagent.model;

import com.codingagent.config.ModelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.ConnectException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对接 OpenAI 兼容的 {@code /chat/completions}，把 SSE 帧翻译成 {@link ChatStreamEvent}。
 *
 * <p>DeepSeek、智谱 GLM、vLLM / SGLang 自部署都走这一条路径，无需各自适配。
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private final String id;
    private final ModelProperties.Provider config;
    private final ObjectMapper mapper;
    private final WebClient webClient;

    public OpenAiCompatibleProvider(String id, ModelProperties.Provider config, ObjectMapper mapper) {
        this.id = id;
        this.config = config;
        this.mapper = mapper;
        this.webClient = buildClient(config);
    }

    private static WebClient buildClient(ModelProperties.Provider config) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs());

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        // 鉴权头必须在这里设。漏了它上游会返回 401 Invalid API Key——
        // 而错误信息看起来像「key 配错了」，很容易去查 key 而不是查代码。
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey().trim());
        }
        return builder.build();
    }

    /**
     * 归一化根地址：去掉尾斜杠，并保证后续拼 {@code /chat/completions} 时不会出现双斜杠。
     * 允许调用方把 {@code /v1} 写进 base-url（部分网关需要）。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("model provider base-url 不能为空");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Flux<ChatStreamEvent> chatStream(ChatRequest request) {
        ObjectNode body = buildRequestBody(request);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(1))
                        .filter(t -> t instanceof ConnectException
                                || (t.getCause() instanceof ConnectException)))
                .<ChatStreamEvent>handle((chunk, sink) -> {
                    try {
                        parseChunk(chunk, sink);
                    } catch (Exception e) {
                        log.warn("[{}] 解析 SSE 帧失败: {}", id, e.getMessage());
                    }
                })
                .concatWith(Flux.just(new ChatStreamEvent.Completed()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.warn("[{}] 上游返回 {}: {}", id, e.getStatusCode().value(), e.getResponseBodyAsString());
                    boolean retryable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
                    String hint = switch (e.getStatusCode().value()) {
                        case 401, 403 -> "模型 API 鉴权失败，请检查 api-key 配置";
                        case 404 -> "模型不存在或 base-url 不正确";
                        case 429 -> "模型 API 触发限流，请稍后重试";
                        default -> "模型调用失败: " + e.getStatusCode().value();
                    };
                    return Flux.just(new ChatStreamEvent.Error(hint, retryable));
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException && e.getMessage() != null
                            && e.getMessage().startsWith("模型")) {
                        return Flux.just(new ChatStreamEvent.Error(e.getMessage(), true));
                    }
                    log.warn("[{}] 模型调用异常: {}", id, e.getMessage());
                    return Flux.just(new ChatStreamEvent.Error("模型调用异常: " + e.getMessage(), true));
                });
    }

    private ObjectNode buildRequestBody(ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model() != null ? request.model() : config.getModel());
        body.put("stream", true);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxOutputTokens() != null) {
            body.put("max_tokens", request.maxOutputTokens());
        }

        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.add(toJson(m));
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ChatRequest.ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", spec.name());
                if (spec.description() != null) {
                    fn.put("description", spec.description());
                }
                fn.set("parameters", mapper.valueToTree(
                        spec.parameters() == null ? Map.of("type", "object", "properties", Map.of()) : spec.parameters()));
            }
        }
        return body;
    }

    private ObjectNode toJson(ChatMessage m) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", m.role().name().toLowerCase());
        if (m.content() != null) {
            node.put("content", m.content());
        } else if (m.role() == ChatMessage.Role.ASSISTANT) {
            // 部分实现要求 assistant 必须有 content 字段，即便只有 tool_calls
            node.putNull("content");
        }
        if (m.toolCallId() != null) {
            node.put("tool_call_id", m.toolCallId());
        }
        if (m.name() != null) {
            node.put("name", m.name());
        }
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            ArrayNode arr = node.putArray("tool_calls");
            for (ToolCall tc : m.toolCalls()) {
                ObjectNode t = arr.addObject();
                t.put("id", tc.id());
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
            }
        }
        return node;
    }

    /**
     * 解析一帧 SSE。
     *
     * <p>两种形态都要认：
     * <ul>
     *   <li>带 {@code data: } 前缀的原始 SSE 行——直接对接上游或某些解码器会这样给</li>
     *   <li>已经剥掉前缀的纯 JSON payload——Spring WebFlux 的
     *       {@code bodyToFlux(String.class)} 对 {@code text/event-stream} 就是这么给的</li>
     * </ul>
     *
     * <p>只认前者会静默丢掉所有事件：解析器按行找 {@code data:} 一个也匹配不到，
     * 既不报错也不产出，表现为「模型调用成功但返回空」——非常难查。
     */
    private void parseChunk(String chunk, SynchronousSink<ChatStreamEvent> sink) {
        BufferedReader reader = new BufferedReader(new StringReader(chunk));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }

                String payload;
                if (line.startsWith("data:")) {
                    payload = line.substring(5).trim();
                } else if (line.startsWith(":")) {
                    // SSE 注释/保活行（如 ": PROCESSING"）
                    continue;
                } else {
                    // 已是裸 JSON payload
                    payload = line.trim();
                }

                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    return;
                }
                parsePayload(payload, sink);
            }
        } catch (Exception e) {
            throw new IllegalStateException("SSE 解析失败: " + e.getMessage(), e);
        }
    }

    private void parsePayload(String payload, SynchronousSink<ChatStreamEvent> sink) throws Exception {
        JsonNode root = mapper.readTree(payload);

        JsonNode error = root.get("error");
        if (error != null) {
            String msg = error.hasNonNull("message") ? error.get("message").asText() : error.toString();
            sink.next(new ChatStreamEvent.Error("上游报错: " + msg, false));
            return;
        }

        JsonNode usage = root.get("usage");
        if (usage != null && usage.has("prompt_tokens")) {
            sink.next(new ChatStreamEvent.Usage(
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0)));
        }

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray()) {
            return;
        }
        for (JsonNode choice : choices) {
            JsonNode delta = choice.get("delta");
            if (delta == null) {
                continue;
            }
            // 推理模型（小米 MiMo 等）把思考过程放在 reasoning_content，
            // 与正文 content 并行下发。这里单独发一个事件供界面展示，
            // 调用方必须丢弃而不能塞回对话历史。
            JsonNode reasoning = delta.get("reasoning_content");
            if (reasoning != null && !reasoning.isNull()) {
                String text = reasoning.asText();
                if (!text.isEmpty()) {
                    sink.next(new ChatStreamEvent.ReasoningDelta(text));
                }
            }

            JsonNode content = delta.get("content");
            if (content != null && !content.isNull()) {
                String text = content.asText();
                if (!text.isEmpty()) {
                    sink.next(new ChatStreamEvent.TextDelta(text));
                }
            }
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    JsonNode fn = tc.get("function");
                    sink.next(new ChatStreamEvent.ToolCallDelta(
                            tc.path("index").asInt(0),
                            tc.path("id").asText(null),
                            fn != null ? fn.path("name").asText(null) : null,
                            fn != null ? fn.path("arguments").asText(null) : null));
                }
            }
        }
    }

    /** 供上层在日志/审计里打印脱敏信息 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("baseUrl", config.getBaseUrl());
        m.put("model", config.getModel());
        m.put("apiKey", mask(config.getApiKey()));
        return m;
    }

    static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "(未配置)";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
