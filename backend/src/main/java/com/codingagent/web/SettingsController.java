package com.codingagent.web;

import com.codingagent.auth.CurrentUser;
import com.codingagent.config.AgentProperties;
import com.codingagent.config.SandboxProperties;
import com.codingagent.model.OpenAiCompatibleProvider;
import com.codingagent.model.ProviderRegistry;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final CurrentUser currentUser;

    public SettingsController(ProviderRegistry providerRegistry,
                              SandboxProperties sandboxProperties,
                              AgentProperties agentProperties,
                              CurrentUser currentUser) {
        this.providerRegistry = providerRegistry;
        this.sandboxProperties = sandboxProperties;
        this.agentProperties = agentProperties;
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
}
