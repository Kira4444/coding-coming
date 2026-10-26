package com.codingagent.model;

import com.codingagent.config.ModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型选择的优先级是承重的：项目覆盖 > 任务类型覆盖 > 默认 provider。
 * 顺序错了，用户在项目里指定了 Token Plan 却会被静默路由到默认模型——
 * 表现为「配置了却没生效」，极难排查。
 */
class ModelRouterTest {

    private static ModelProperties props(String defaultProvider, Map<String, String> taskOverrides) {
        ModelProperties p = new ModelProperties();
        Map<String, ModelProperties.Provider> providers = new LinkedHashMap<>();
        for (String id : new String[]{"mimo", "mimo-token-plan"}) {
            ModelProperties.Provider cfg = new ModelProperties.Provider();
            cfg.setBaseUrl("https://example.test/" + id);
            cfg.setApiKey("k-" + id);
            cfg.setModel("mimo-v2.6-flash");
            providers.put(id, cfg);
        }
        p.setProviders(providers);
        p.setDefaultProvider(defaultProvider);
        p.setTaskOverrides(taskOverrides);
        return p;
    }

    private static ModelRouter router(ModelProperties props) {
        return new ModelRouter(new ProviderRegistry(props, new ObjectMapper()), props);
    }

    @Test
    void 项目覆盖优先于任务覆盖和默认() {
        ModelProperties props = props("mimo", Map.of("code", "mimo/mimo-v2.6-flash"));
        ModelRouter r = router(props);

        ModelRouter.ResolvedModel resolved = r.resolve("code", "mimo-token-plan/mimo-v2.6-pro");

        assertEquals("mimo-token-plan", resolved.provider().id());
        assertEquals("mimo-v2.6-pro", resolved.model());
    }

    @Test
    void 无项目覆盖时走任务覆盖() {
        ModelProperties props = props("mimo", Map.of("code", "mimo-token-plan/mimo-v2.6-flash"));
        ModelRouter r = router(props);

        ModelRouter.ResolvedModel resolved = r.resolve("code", null);

        assertEquals("mimo-token-plan", resolved.provider().id());
    }

    @Test
    void 都没覆盖时走默认provider() {
        ModelProperties props = props("mimo", Map.of());
        ModelRouter r = router(props);

        ModelRouter.ResolvedModel resolved = r.resolve("code", null);

        assertEquals("mimo", resolved.provider().id());
        assertEquals("mimo-v2.6-flash", resolved.model());
    }

    @Test
    void 空白项目覆盖视为未配置() {
        ModelProperties props = props("mimo", Map.of());
        ModelRouter r = router(props);

        assertEquals("mimo", r.resolve(null, "   ").provider().id());
        assertEquals("mimo", r.resolve(null, "").provider().id());
    }

    @Test
    void 格式不对要报错而不是静默回退() {
        ModelProperties props = props("mimo", Map.of());
        ModelRouter r = router(props);

        // 少了 modelName —— 静默回退会让用户以为配置生效了
        assertThrows(IllegalArgumentException.class, () -> r.resolve(null, "mimo-token-plan"));
        // 少了 providerId
        assertThrows(IllegalArgumentException.class, () -> r.resolve(null, "/mimo-v2.6-pro"));
    }

    @Test
    void 指向未配置的provider要报错() {
        ModelProperties props = props("mimo", Map.of());
        ModelRouter r = router(props);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.resolve(null, "no-such-provider/mimo-v2.6-pro"));
        assertTrue(e.getMessage().contains("no-such-provider"));
    }

    @Test
    void 不带项目覆盖的旧签名仍然可用() {
        ModelProperties props = props("mimo", Map.of());
        ModelRouter r = router(props);

        assertEquals("mimo", r.resolve(null).provider().id());
    }
}
