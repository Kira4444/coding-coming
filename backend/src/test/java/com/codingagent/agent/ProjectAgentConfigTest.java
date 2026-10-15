package com.codingagent.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 排除路径匹配是「看着简单、写起来容易漏」的那类逻辑。
 * 这里的用例按「用户会怎么写配置」组织，而不是按实现分支。
 */
class ProjectAgentConfigTest {

    private static ProjectAgentConfig cfg(String exclude) {
        return new ProjectAgentConfig(null, Set.of(), null, null, null,
                ProjectAgentConfig.parseList(exclude));
    }

    @Test
    void 目录名匹配其下所有内容() {
        ProjectAgentConfig c = cfg("node_modules");
        assertTrue(c.isExcluded("node_modules"));
        assertTrue(c.isExcluded("node_modules/express/index.js"));
        assertFalse(c.isExcluded("src/node_modules_bak/a.js"));
        assertFalse(c.isExcluded("src/main.js"));
    }

    @Test
    void 无斜杠模式匹配任意层级() {
        // 对齐 gitignore 直觉：写 node_modules 就该把所有层级的都排掉
        ProjectAgentConfig c = cfg("node_modules");
        assertTrue(c.isExcluded("src/node_modules/express/index.js"));
        assertTrue(c.isExcluded("a/b/c/node_modules/x.js"));
    }

    @Test
    void 含斜杠模式锚定在工作区根() {
        ProjectAgentConfig c = cfg("src/generated");
        assertTrue(c.isExcluded("src/generated/a.java"));
        assertFalse(c.isExcluded("x/src/generated/a.java"));
    }

    @Test
    void 带斜杠的目录配置等价() {
        ProjectAgentConfig c = cfg("node_modules/");
        assertTrue(c.isExcluded("node_modules/express/index.js"));
    }

    @Test
    void 星号单段匹配() {
        ProjectAgentConfig c = cfg("*.log");
        assertTrue(c.isExcluded("build.log"));
        assertTrue(c.isExcluded("a/b/error.log"));
        assertFalse(c.isExcluded("log/build.txt"));
    }

    @Test
    void 双星跨段匹配() {
        ProjectAgentConfig c = cfg("**/generated/**");
        assertTrue(c.isExcluded("generated/a.java"));
        assertTrue(c.isExcluded("src/main/generated/a.java"));
        assertFalse(c.isExcluded("src/main/java/a.java"));
    }

    @Test
    void 多条配置逗号换行都能分隔() {
        ProjectAgentConfig c = cfg("node_modules, *.log\n dist ");
        assertTrue(c.isExcluded("node_modules/x"));
        assertTrue(c.isExcluded("a.log"));
        assertTrue(c.isExcluded("dist/index.js"));
        assertFalse(c.isExcluded("src/index.js"));
    }

    @Test
    void 空配置不排斥任何路径() {
        ProjectAgentConfig c = cfg(null);
        assertFalse(c.isExcluded("node_modules/x"));
        assertFalse(c.isExcluded(""));
    }

    @Test
    void 路径前缀的点斜杠被归一化() {
        ProjectAgentConfig c = cfg("build");
        assertTrue(c.isExcluded("./build/out.js"));
        assertTrue(c.isExcluded("build\\out.js")); // Windows 分隔符
    }

    @Test
    void 工具白名单为空表示全部启用() {
        ProjectAgentConfig c = new ProjectAgentConfig(null, Set.of(), null, null, null, Set.of());
        assertTrue(c.isToolEnabled("shell"));
        assertTrue(c.isToolEnabled("write_file"));
    }

    @Test
    void 工具白名单只放行列出的() {
        ProjectAgentConfig c = new ProjectAgentConfig(
                null, Set.of("read_file", "grep"), null, null, null, Set.of());
        assertTrue(c.isToolEnabled("read_file"));
        assertTrue(c.isToolEnabled("grep"));
        assertFalse(c.isToolEnabled("shell"));
        assertFalse(c.isToolEnabled("write_file"));
    }

    @Test
    void 项目预算覆盖全局() {
        AgentLimits global = new AgentLimits(40, 500_000, 200, 20_000, 500, 100);

        ProjectAgentConfig noOverride = new ProjectAgentConfig(null, Set.of(), null, null, null, Set.of());
        assertEquals(40, noOverride.applyTo(global).maxTurns());
        assertEquals(500_000, noOverride.applyTo(global).tokenBudget());

        ProjectAgentConfig override = new ProjectAgentConfig(null, Set.of(), null, 12, 999L, Set.of());
        assertEquals(12, override.applyTo(global).maxTurns());
        assertEquals(999L, override.applyTo(global).tokenBudget());

        // 未被覆盖的字段保持全局值
        assertEquals(200, override.applyTo(global).shellOutputMaxLines());
    }

    @Test
    void 零值或负值覆盖视为未设置() {
        AgentLimits global = new AgentLimits(40, 500_000, 200, 20_000, 500, 100);
        ProjectAgentConfig c = new ProjectAgentConfig(null, Set.of(), null, 0, -1L, Set.of());
        assertEquals(40, c.applyTo(global).maxTurns());
        assertEquals(500_000, c.applyTo(global).tokenBudget());
    }

    @Test
    void 指令为空时返回null() {
        assertNull(new ProjectAgentConfig("  ", Set.of(), null, null, null, Set.of()).instructions());
        assertEquals("用 pnpm",
                new ProjectAgentConfig("  用 pnpm  ", Set.of(), null, null, null, Set.of()).instructions());
    }
}
