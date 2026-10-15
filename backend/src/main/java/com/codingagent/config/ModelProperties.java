package com.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型接入配置。
 *
 * <p>配置结构按「默认模型 / 按任务类型覆盖 / 按项目覆盖」三层预留，
 * MVP 只实际填默认那一层，但数据形状从第一天就定好，免得日后加路由要改表结构。
 *
 * <pre>
 * coding-agent:
 *   model:
 *     default-provider: deepseek
 *     providers:
 *       deepseek:
 *         base-url: https://api.deepseek.com
 *         api-key: ${DEEPSEEK_API_KEY}
 *         model: deepseek-chat
 *       glm:
 *         base-url: https://open.bigmodel.cn/api/paas/v4
 *         api-key: ${GLM_API_KEY}
 *         model: glm-4.5
 *     task-overrides:
 *       plan: glm/glm-4.5
 *       summary: deepseek/deepseek-chat
 * </pre>
 */
@ConfigurationProperties(prefix = "coding-agent.model")
public class ModelProperties {

    /** 默认 provider id，对应 {@link #providers} 的 key */
    private String defaultProvider = "deepseek";

    private Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * 按任务类型覆盖，值形如 {@code "providerId/modelName"}。
     * 未列出的任务类型一律走默认 provider。
     */
    private Map<String, String> taskOverrides = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public Map<String, String> getTaskOverrides() {
        return taskOverrides;
    }

    public void setTaskOverrides(Map<String, String> taskOverrides) {
        this.taskOverrides = taskOverrides;
    }

    public static class Provider {

        /** OpenAI 兼容根地址，不带 /chat/completions */
        private String baseUrl;

        /** 明文只允许来自环境变量；入库时必须加密 */
        private String apiKey;

        /** 该 provider 下的默认模型名 */
        private String model;

        /** 连接超时（毫秒） */
        private int connectTimeoutMs = 10_000;

        /** 单次请求整体超时（毫秒），流式场景给足余量 */
        private int readTimeoutMs = 300_000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
