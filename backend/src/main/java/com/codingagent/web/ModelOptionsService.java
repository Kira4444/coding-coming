package com.codingagent.web;

import com.codingagent.config.ModelProperties;
import com.codingagent.model.OpenAiCompatibleProvider;
import com.codingagent.model.ProviderRegistry;
import com.codingagent.web.SettingsController.ModelOptionView;
import com.codingagent.web.SettingsController.ModelOptionsView;
import com.codingagent.web.SettingsController.ModelSource;
import com.codingagent.web.SettingsController.ProviderModelsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目「模型覆盖」下拉框的候选列表：按 provider 各自拉上游 {@code /models}。
 *
 * <p>按 provider 独立降级，一个 provider 拉不到列表不影响其他 provider——
 * 部分网关压根不实现 /models 但 /chat/completions 正常，那种 provider 仍要能选到
 * 默认模型。整个接口也因此恒 200：下拉框少几个选项远好过设置页整体报错。
 *
 * <p>缓存的是上游 /models 的原始结果（成功 id 列表或失败标记）；
 * defaultModel 并入、source/warning/selectable 的组装在每次读取时做，
 * 组装逻辑改了不会脏缓存。UNCONFIGURED 不进缓存也不发 I/O——无 key 判定是纯本地的。
 */
@Service
public class ModelOptionsService {

    private static final Logger log = LoggerFactory.getLogger(ModelOptionsService.class);

    /** 成功结果缓存 10 分钟。模型列表变动不频繁，但也不能永久——provider 上新模型得能被看到。 */
    private static final Duration SUCCESS_TTL = Duration.ofMinutes(10);

    /**
     * 失败结果缓存 60 秒。短到临时网络故障能快速恢复，长到挡住反复打开设置页的重试风暴。
     */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(60);

    private final ProviderRegistry providerRegistry;

    /** key = providerId，各 provider 各自一份，互不影响 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ModelOptionsService(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    /** 上游拉取失败的类别。决定 FALLBACK 时给用户看哪条 hint。 */
    private enum FailureKind { AUTH, NOT_FOUND, RATE_LIMITED, OTHER }

    /**
     * 缓存值：成功记 id 列表，失败记类别。expiresAt 之前都可复用。
     */
    private record CacheEntry(List<String> ids, FailureKind failure, Instant expiresAt) {

        static CacheEntry success(List<String> ids, Instant now) {
            return new CacheEntry(List.copyOf(ids), null, now.plus(SUCCESS_TTL));
        }

        static CacheEntry failure(FailureKind kind, Instant now) {
            return new CacheEntry(null, kind, now.plus(FAILURE_TTL));
        }

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * 组装模型下拉框数据。
     *
     * @param forceRefresh 为 true 时跳过缓存强拉全部 provider 并回填缓存——
     *                     供「provider 刚上新模型、想立刻看到」的场景
     */
    public Mono<ModelOptionsView> load(boolean forceRefresh) {
        ModelProperties properties = providerRegistry.properties();
        // 遍历配置表而不是 registry.all()：注册失败的 provider 也得出现在响应里，
        // 前端要显示「未配置」，不能凭空消失。LinkedHashMap 保序 = application.yml 声明顺序。
        Map<String, ModelProperties.Provider> configured = properties.getProviders();
        List<String> order = new ArrayList<>(configured.keySet());

        List<Mono<ProviderModelsView>> monos = new ArrayList<>(order.size());
        for (String providerId : order) {
            monos.add(providerView(providerId, configured.get(providerId), forceRefresh));
        }
        if (monos.isEmpty()) {
            return Mono.just(new ModelOptionsView(properties.getDefaultProvider(), List.of()));
        }
        // zip 保序：providers 数组必须按声明顺序返回，前端按顺序渲染 optgroup
        return Mono.zip(monos, arr -> {
            List<ProviderModelsView> views = new ArrayList<>(arr.length);
            for (Object o : arr) {
                views.add((ProviderModelsView) o);
            }
            return new ModelOptionsView(properties.getDefaultProvider(), views);
        });
    }

    /**
     * 单个 provider 的视图。永远以值收尾、永不报错——单个 provider 的问题不能抬高 HTTP 状态码。
     */
    private Mono<ProviderModelsView> providerView(String providerId, ModelProperties.Provider cfg,
                                                  boolean forceRefresh) {
        try {
            String defaultModel = cfg.getModel() == null ? "" : cfg.getModel();
            boolean configured = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
            if (!configured) {
                // 没 api-key 就不碰上游：省一次注定 401 的请求
                return Mono.just(unconfiguredView(providerId, defaultModel));
            }
            return loadCached(providerId, forceRefresh)
                    .map(entry -> assemble(providerId, defaultModel, entry))
                    // 兜底：组装或上游链路上任何未预期的异常都降级，不能让一个 provider 把接口抬成 500
                    .onErrorResume(e -> {
                        log.warn("[{}] 模型列表降级: {}", providerId, e.getMessage());
                        return Mono.just(new ProviderModelsView(providerId, true, defaultModel,
                                ModelSource.FALLBACK, fallbackWarning(defaultModel), fallbackModels(providerId, defaultModel)));
                    });
        } catch (Exception e) {
            log.warn("[{}] 组装模型列表失败: {}", providerId, e.getMessage());
            String defaultModel = cfg.getModel() == null ? "" : cfg.getModel();
            return Mono.just(new ProviderModelsView(providerId, true, defaultModel,
                    ModelSource.FALLBACK, fallbackWarning(defaultModel),
                    fallbackModels(providerId, defaultModel)));
        }
    }

    /**
     * 降级文案。没有默认模型可退时不能说「已退回默认模型」——那是假话，
     * 而且会让用户以为下拉里还有得选。
     */
    private static String fallbackWarning(String defaultModel) {
        return defaultModel == null || defaultModel.isEmpty()
                ? "未配置默认模型且模型列表拉取失败"
                : "模型列表拉取失败，已退回默认模型";
    }

    /** 退回默认模型兜底（保证下拉非空）；没有默认模型时只能空着——假 id 存进 modelOverride 是必然失败的解析 */
    private static List<ModelOptionView> fallbackModels(String providerId, String defaultModel) {
        return defaultModel.isEmpty()
                ? List.of()
                : List.of(new ModelOptionView(defaultModel, providerId + "/" + defaultModel, true, true));
    }

    /** 取缓存或拉上游并回填缓存。返回的 entry 不为 null、不带异常。 */
    private Mono<CacheEntry> loadCached(String providerId, boolean forceRefresh) {
        return Mono.defer(() -> {
            Instant now = Instant.now();
            if (!forceRefresh) {
                CacheEntry hit = cache.get(providerId);
                if (hit != null && !hit.expired()) {
                    return Mono.just(hit);
                }
            }
            OpenAiCompatibleProvider provider = providerRegistry.all().get(providerId);
            if (provider == null) {
                // 构造期注册失败的 provider（base-url 配错之类），视同拉取失败
                return Mono.just(put(providerId, CacheEntry.failure(FailureKind.OTHER, now)));
            }
            return provider.listModels()
                    // data 为空数组也算失败：空列表进下拉框只会渲染出一个空 optgroup
                    .map(ids -> put(providerId, ids.isEmpty()
                            ? CacheEntry.failure(FailureKind.OTHER, now)
                            : CacheEntry.success(ids, now)))
                    .onErrorResume(e -> {
                        FailureKind kind = classify(e);
                        log.warn("[{}] 拉取模型列表失败({}): {}", providerId, kind, e.getMessage());
                        return Mono.just(put(providerId, CacheEntry.failure(kind, now)));
                    });
        });
    }

    private CacheEntry put(String providerId, CacheEntry entry) {
        cache.put(providerId, entry);
        return entry;
    }

    /** 把缓存的原始结果组装成视图。每次读取都做，保证组装逻辑变更不脏缓存。 */
    private ProviderModelsView assemble(String providerId, String defaultModel, CacheEntry entry) {
        String valuePrefix = providerId + "/";

        if (entry.failure() != null) {
            // 已配置但列表拉不到：退回默认模型兜底。provider 本身仍可用（对话走的是
            // /chat/completions），所以默认模型保持可选。
            String warning = defaultModel.isEmpty()
                    ? "未配置默认模型且模型列表拉取失败"
                    : hintFor(entry.failure());
            return new ProviderModelsView(providerId, true, defaultModel,
                    ModelSource.FALLBACK, warning, fallbackModels(providerId, defaultModel));
        }

        // 上游成功：按返回顺序去重；默认模型不在列表里则置顶补上——用户当前正在用的就是它
        LinkedHashSet<String> unique = new LinkedHashSet<>(entry.ids());
        List<ModelOptionView> models = new ArrayList<>(unique.size() + 1);
        if (!defaultModel.isEmpty() && !unique.contains(defaultModel)) {
            models.add(new ModelOptionView(defaultModel, valuePrefix + defaultModel, true, true));
        }
        for (String modelId : unique) {
            models.add(new ModelOptionView(modelId, valuePrefix + modelId,
                    modelId.equals(defaultModel), true));
        }
        return new ProviderModelsView(providerId, true, defaultModel,
                ModelSource.UPSTREAM, null, models);
    }

    /** 无 api-key：不发 I/O、不进缓存，但要出现在响应里，前端要显示「未配置」 */
    private static ProviderModelsView unconfiguredView(String providerId, String defaultModel) {
        List<ModelOptionView> models = defaultModel.isEmpty()
                ? List.of()
                : List.of(new ModelOptionView(defaultModel, providerId + "/" + defaultModel, true, false));
        return new ProviderModelsView(providerId, false, defaultModel,
                ModelSource.UNCONFIGURED, "未配置 API Key，该 provider 的模型不可用", models);
    }

    private static String hintFor(FailureKind kind) {
        return switch (kind) {
            case AUTH -> "模型 API 鉴权失败，请检查 api-key 配置";
            case NOT_FOUND -> "模型列表不可用或 base-url 不正确，已退回默认模型";
            case RATE_LIMITED -> "模型 API 触发限流，请稍后重试";
            case OTHER -> "模型列表拉取失败，已退回默认模型";
        };
    }

    /** 从异常里剥出 HTTP 状态码。超时、连接失败、解析失败统一落 OTHER。 */
    private static FailureKind classify(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 5; depth++) {
            if (t instanceof WebClientResponseException w) {
                return switch (w.getStatusCode().value()) {
                    case 401, 403 -> FailureKind.AUTH;
                    case 404 -> FailureKind.NOT_FOUND;
                    case 429 -> FailureKind.RATE_LIMITED;
                    default -> FailureKind.OTHER;
                };
            }
            t = t.getCause();
        }
        return FailureKind.OTHER;
    }
}
