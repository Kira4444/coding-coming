package com.codingagent.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目级 agent 配置的解析结果。
 *
 * <p>从 {@code Project} 实体折算而来，agent 层只依赖这个不可变对象，
 * 不去碰数据库实体——领域边界干净，单测也好写。
 */
public final class ProjectAgentConfig {

    /** 空配置：全部用全局默认 */
    public static final ProjectAgentConfig DEFAULT = new ProjectAgentConfig(
            null, Collections.emptySet(), null, null, null, Collections.emptySet(), null);

    private final String instructions;
    private final Set<String> enabledTools;
    private final String approvalPolicy;
    private final Integer maxTurns;
    private final Long tokenBudget;
    private final Set<String> excludePaths;
    /** 项目级模型覆盖，形如 {@code providerId/modelName}。空则走全局默认。 */
    private final String modelOverride;

    public ProjectAgentConfig(String instructions,
                              Set<String> enabledTools,
                              String approvalPolicy,
                              Integer maxTurns,
                              Long tokenBudget,
                              Set<String> excludePaths) {
        this(instructions, enabledTools, approvalPolicy, maxTurns, tokenBudget, excludePaths, null);
    }

    public ProjectAgentConfig(String instructions,
                              Set<String> enabledTools,
                              String approvalPolicy,
                              Integer maxTurns,
                              Long tokenBudget,
                              Set<String> excludePaths,
                              String modelOverride) {
        this.instructions = (instructions == null || instructions.isBlank()) ? null : instructions.trim();
        this.enabledTools = enabledTools == null ? Collections.emptySet() : Set.copyOf(enabledTools);
        this.approvalPolicy = (approvalPolicy == null || approvalPolicy.isBlank()) ? null : approvalPolicy.trim();
        this.maxTurns = maxTurns;
        this.tokenBudget = tokenBudget;
        this.excludePaths = excludePaths == null ? Collections.emptySet() : Set.copyOf(excludePaths);
        this.modelOverride = (modelOverride == null || modelOverride.isBlank()) ? null : modelOverride.trim();
    }

    /** 从逗号/换行分隔的字符串解析列表，空串返回空集 */
    public static Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,\\n\\r]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public String instructions() {
        return instructions;
    }

    /** 启用的工具名。空集表示「不限制」，即全部启用。 */
    public Set<String> enabledTools() {
        return enabledTools;
    }

    public boolean isToolEnabled(String toolName) {
        return enabledTools.isEmpty() || enabledTools.contains(toolName);
    }

    public String approvalPolicy() {
        return approvalPolicy;
    }

    public Integer maxTurns() {
        return maxTurns;
    }

    public Long tokenBudget() {
        return tokenBudget;
    }

    public Set<String> excludePaths() {
        return excludePaths;
    }

    /**
     * 项目级模型覆盖，形如 {@code providerId/modelName}。
     * 返回 {@code null} 表示没配，走全局默认 provider。
     */
    public String modelOverride() {
        return modelOverride;
    }

    /**
     * 某个相对路径是否被排除。
     *
     * <p>匹配规则刻意简单：支持 {@code * } 单段通配与 {@code **} 跨段通配，
     * 以及「等于某目录前缀」的判断。不做完整 glob 引擎——那是过度设计，
     * 用户真有复杂需求时该用沙箱镜像把东西删掉。
     */
    public boolean isExcluded(String relativePath) {
        if (excludePaths.isEmpty() || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String pattern : excludePaths) {
            if (matches(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匹配语义对齐 gitignore 的直觉：
     * <ul>
     *   <li>不含 {@code /} 的模式 —— 匹配任意层级的<b>文件名</b>，
     *       因此 {@code node_modules} 会命中 {@code src/node_modules/x}，
     *       {@code *.log} 会命中 {@code a/b/error.log}</li>
     *   <li>含 {@code /} 的模式 —— 从工作区根开始匹配完整路径（锚定）</li>
     * </ul>
     */
    private static boolean matches(String pattern, String path) {
        String p = pattern.replace('\\', '/').trim();
        if (p.isEmpty()) {
            return false;
        }

        boolean hasSlash = p.indexOf('/') >= 0;
        boolean hasStar = p.indexOf('*') >= 0;

        if (!hasSlash) {
            // 逐段比较文件名
            String[] segs = path.split("/");
            for (String seg : segs) {
                if (hasStar ? segMatch(p, seg) : seg.equals(p)) {
                    return true;
                }
            }
            return false;
        }

        String normalized = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        if (hasStar) {
            return globMatch(normalized, path);
        }
        String prefix = normalized + "/";
        return path.equals(normalized) || path.startsWith(prefix);
    }

    /** 极简 glob：** 跨段，* 不跨段 */
    private static boolean globMatch(String pattern, String path) {
        // 把 ** 翻成一个不跨段约束的占位，再逐段匹配
        String[] pParts = pattern.split("/", -1);
        String[] sParts = path.split("/", -1);
        return matchParts(pParts, 0, sParts, 0);
    }

    private static boolean matchParts(String[] p, int pi, String[] s, int si) {
        while (pi < p.length && si < s.length) {
            String seg = p[pi];
            if (seg.equals("**")) {
                // ** 可以吞掉 0..n 段
                if (pi == p.length - 1) {
                    return true;
                }
                for (int k = si; k <= s.length; k++) {
                    if (matchParts(p, pi + 1, s, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (!segMatch(seg, s[si])) {
                return false;
            }
            pi++;
            si++;
        }
        // 尾部 ** 吞掉剩余
        while (pi < p.length && p[pi].equals("**")) {
            pi++;
        }
        return pi == p.length && si == s.length;
    }

    private static boolean segMatch(String pattern, String seg) {
        if (pattern.equals("*")) {
            return true;
        }
        // 单段内 * 通配
        String[] tokens = pattern.split("\\*", -1);
        int idx = 0;
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.isEmpty()) {
                continue;
            }
            if (i == 0) {
                if (!seg.startsWith(tok)) {
                    return false;
                }
                idx = tok.length();
            } else if (i == tokens.length - 1) {
                if (!seg.endsWith(tok) || seg.length() - tok.length() < idx) {
                    return false;
                }
            } else {
                int found = seg.indexOf(tok, idx);
                if (found < 0) {
                    return false;
                }
                idx = found + tok.length();
            }
        }
        return true;
    }

    /** 应用项目覆盖到全局预算上 */
    public AgentLimits applyTo(AgentLimits global) {
        return new AgentLimits(
                maxTurns != null && maxTurns > 0 ? maxTurns : global.maxTurns(),
                tokenBudget != null && tokenBudget > 0 ? tokenBudget : global.tokenBudget(),
                global.shellOutputMaxLines(),
                global.shellOutputMaxChars(),
                global.fileReadMaxLines(),
                global.searchMaxResults());
    }

    public List<String> enabledToolsList() {
        return List.copyOf(enabledTools);
    }

    public List<String> excludePathsList() {
        return List.copyOf(excludePaths);
    }
}
