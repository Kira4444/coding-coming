package com.codingagent.web;

import com.codingagent.auth.CurrentUser;
import com.codingagent.config.AgentProperties;
import com.codingagent.config.SandboxProperties;
import com.codingagent.model.OpenAiCompatibleProvider;
import com.codingagent.model.ProviderRegistry;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设置页数据。
 *
 * <p>只读。改配置走环境变量 / 配置文件——让运行中的服务自己改自己的配置，
 * 重启后就丢了，是个会咬人的设计。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ProviderRegistry providerRegistry;
    private final SandboxProperties sandboxProperties;
    private final AgentProperties agentProperties;
    private final ModelOptionsService modelOptionsService;
    private final CurrentUser currentUser;

    public SettingsController(ProviderRegistry providerRegistry,
                              SandboxProperties sandboxProperties,
                              AgentProperties agentProperties,
                              ModelOptionsService modelOptionsService,
                              CurrentUser currentUser) {
        this.providerRegistry = providerRegistry;
        this.sandboxProperties = sandboxProperties;
        this.agentProperties = agentProperties;
        this.modelOptionsService = modelOptionsService;
        this.currentUser = currentUser;
    }

    public record ProviderView(
            String id,
            String baseUrl,
            String model,
            String apiKeyMasked,
            boolean configured
    ) {
    }

    public record SettingsView(
            List<ProviderView> providers,
            String defaultProvider,
            Map<String, String> taskOverrides,
            String sandboxDriver,
            String sandboxImage,
            String sandboxNetwork,
            int sandboxCpu,
            String sandboxMemory,
            int maxConcurrentSessions,
            int maxTurns,
            long tokenBudget
    ) {
    }

    // ---------- 模型覆盖下拉框 ----------

    /**
     * 模型列表来源：
     * UPSTREAM = 上游 /models 拉取成功；FALLBACK = 已配置但拉取失败（退回默认模型）；
     * UNCONFIGURED = 无 api-key，未调上游。
     */
    public enum ModelSource { UPSTREAM, FALLBACK, UNCONFIGURED }

    /**
     * 下拉框里的一个模型选项。
     *
     * <p>{@code value} 由后端拼好 {@code providerId/modelId}，前端原样使用、不做切分——
     * modelId 自身可能带 {@code /}（自部署常见 "Qwen/Qwen-72B"），前端切分会切错。
     */
    public record ModelOptionView(
            String modelId,
            String value,
            boolean isDefault,
            boolean selectable
    ) {
    }

    /** 单个 provider 的模型分组。接口恒 200，单个 provider 的问题降级进 source/warning。 */
    public record ProviderModelsView(
            String providerId,
            boolean configured,
            String defaultModel,
            ModelSource source,
            String warning,
            List<ModelOptionView> models
    ) {
    }

    /** 模型覆盖下拉框的整体数据。绝不带 apiKey 字段——连掩码都不带。 */
    public record ModelOptionsView(
            String defaultProvider,
            List<ProviderModelsView> providers
    ) {
    }

    @GetMapping
    public Mono<SettingsView> settings(ServerHttpRequest request) {
        currentUser.requireUserId(request);

        List<ProviderView> providers = new ArrayList<>();
        for (Map.Entry<String, OpenAiCompatibleProvider> e : providerRegistry.all().entrySet()) {
            Map<String, Object> desc = e.getValue().describe();
            String keyMasked = String.valueOf(desc.getOrDefault("apiKey", ""));
            boolean configured = keyMasked != null && !keyMasked.isEmpty()
                    && !keyMasked.equals("(未配置)");
            providers.add(new ProviderView(
                    e.getKey(),
                    String.valueOf(desc.getOrDefault("baseUrl", "")),
                    String.valueOf(desc.getOrDefault("model", "")),
                    keyMasked,
                    configured));
        }

        return Mono.just(new SettingsView(
                providers,
                providerRegistry.properties().getDefaultProvider(),
                providerRegistry.properties().getTaskOverrides(),
                sandboxProperties.getDriver(),
                sandboxProperties.getDefaultImage(),
                sandboxProperties.getNetwork(),
                sandboxProperties.getCpu(),
                sandboxProperties.getMemory(),
                sandboxProperties.getMaxConcurrentSessions(),
                agentProperties.getMaxTurns(),
                agentProperties.getTokenBudget()));
    }

    /**
     * 项目「模型覆盖」下拉框的候选列表：按 provider 拉上游 /models 组装。
     *
     * <p>鉴权与 {@link #settings} 相同。恒 200——单个 provider 拉取失败降级进
     * source/warning，不抬高 HTTP 状态码：下拉框少几个选项远好过设置页整体报错。
     *
     * @param refresh 为 true 时跳过缓存强拉全部 provider 并回填缓存
     */
    @GetMapping("/model-options")
    public Mono<ModelOptionsView> modelOptions(ServerHttpRequest request,
                                              @RequestParam(name = "refresh", required = false) boolean refresh) {
        currentUser.requireUserId(request);
        return modelOptionsService.load(refresh);
    }
}
