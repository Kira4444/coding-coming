package com.codingagent.agent;

import com.codingagent.config.AgentProperties;

/** 把配置对象折算成 {@link AgentLimits}，避免 agent 层依赖 Spring 配置类。 */
public final class AgentPropertiesMapper {

    private AgentPropertiesMapper() {
    }

    public static AgentLimits toLimits(AgentProperties p) {
        return new AgentLimits(
                p.getMaxTurns(),
                p.getTokenBudget(),
                p.getShellOutputMaxLines(),
                p.getShellOutputMaxChars(),
                p.getFileReadMaxLines(),
                p.getSearchMaxResults());
    }
}
