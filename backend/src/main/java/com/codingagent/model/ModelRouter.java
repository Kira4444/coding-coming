package com.codingagent.model;

import com.codingagent.config.ModelProperties;
import org.springframework.stereotype.Component;

/**
 * 决定一次调用该用哪个 provider + 哪个模型。
 *
 * <p>解析顺序（MVP 只实际用到第 1、2 层，第 3 层的数据结构已预留）：
 * <ol>
 *   <li>项目级覆盖 —— 尚未接入，留接口</li>
 *   <li>任务类型覆盖 —— {@code coding-agent.model.task-overrides}</li>
 *   <li>provider 默认模型</li>
 * </ol>
 */
@Component
public class ModelRouter {

    /** 任务类型常量。新增任务要同时考虑是否需要加 override 配置项。 */
    public static final String TASK_PLAN = "plan";
    public static final String TASK_CODE = "code";
    public static final String TASK_SUMMARY = "summary";

    private final ProviderRegistry registry;
    private final ModelProperties properties;

    public ModelRouter(ProviderRegistry registry, ModelProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /** 解析出实际调用目标 */
    public ResolvedModel resolve(String taskType) {
        String override = taskType == null ? null : properties.getTaskOverrides().get(taskType);
        if (override != null && !override.isBlank()) {
            String[] parts = override.split("/", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "task-overrides 取值应形如 providerId/modelName，当前: " + override);
            }
            return new ResolvedModel(registry.require(parts[0].trim()), parts[1].trim());
        }
        LlmProvider provider = registry.defaultProvider();
        ModelProperties.Provider cfg = properties.getProviders().get(provider.id());
        String model = cfg != null ? cfg.getModel() : null;
        return new ResolvedModel(provider, model);
    }

    /** 项目级覆盖的扩展点。MVP 恒返回空，二期接项目设置表。 */
    public java.util.Optional<String> projectOverride(String projectId, String taskType) {
        return java.util.Optional.empty();
    }

    public record ResolvedModel(LlmProvider provider, String model) {
    }
}
