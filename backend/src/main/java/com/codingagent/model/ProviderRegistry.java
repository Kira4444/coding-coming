package com.codingagent.model;

import com.codingagent.config.ModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 启动时按配置装配 provider 实例。
 *
 * <p>配置缺失不阻断启动：没配 api-key 时该 provider 仍注册，但调用时会给出明确报错，
 * 这样「先起服务再补密钥」的工作流不至于崩在启动阶段。
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, OpenAiCompatibleProvider> providers = new LinkedHashMap<>();
    private final ModelProperties properties;

    public ProviderRegistry(ModelProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        properties.getProviders().forEach((id, cfg) -> {
            try {
                providers.put(id, new OpenAiCompatibleProvider(id, cfg, mapper));
                log.info("注册模型 provider [{}] base-url={} model={}",
                        id, cfg.getBaseUrl(), cfg.getModel());
            } catch (Exception e) {
                log.error("注册模型 provider [{}] 失败: {}", id, e.getMessage());
            }
        });
        if (providers.isEmpty()) {
            log.warn("未配置任何模型 provider，请在 coding-agent.model.providers 下补充");
        }
    }

    public Optional<LlmProvider> find(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    public LlmProvider require(String providerId) {
        LlmProvider p = providers.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException(
                    "模型 provider [" + providerId + "] 未配置，已配置: " + providers.keySet());
        }
        return p;
    }

    /** 默认 provider；未指定任务类型时走这里 */
    public LlmProvider defaultProvider() {
        return require(properties.getDefaultProvider());
    }

    public Map<String, OpenAiCompatibleProvider> all() {
        return Collections.unmodifiableMap(providers);
    }

    public ModelProperties properties() {
        return properties;
    }
}
