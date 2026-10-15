package com.codingagent.agent;

/**
 * 截断与预算限制。全部来自配置，不做硬编码——
 * 没有这些上限，一次 {@code npm test} 的输出就能吃掉几万 token。
 */
public record AgentLimits(
        int maxTurns,
        long tokenBudget,
        int shellOutputMaxLines,
        int shellOutputMaxChars,
        int fileReadMaxLines,
        int searchMaxResults
) {
}
