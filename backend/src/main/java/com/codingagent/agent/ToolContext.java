package com.codingagent.agent;

import com.codingagent.sandbox.SandboxSession;
import com.codingagent.skill.SkillRegistry;

/**
 * 工具执行期的上下文。工具通过它访问沙箱、项目配置与技能表，
 * 而不是各自去 new 连接——生命周期归 agent loop 管，工具只管干活。
 */
public record ToolContext(
        SandboxSession sandbox,
        AgentLimits limits,
        ProjectAgentConfig projectConfig,
        SkillRegistry skills
) {

    public ToolContext(SandboxSession sandbox, AgentLimits limits) {
        this(sandbox, limits, ProjectAgentConfig.DEFAULT, SkillRegistry.empty());
    }

    public ToolContext(SandboxSession sandbox, AgentLimits limits, ProjectAgentConfig projectConfig) {
        this(sandbox, limits, projectConfig, SkillRegistry.empty());
    }

    /** 该相对路径是否被项目配置排除 */
    public boolean isExcluded(String relativePath) {
        return projectConfig != null && projectConfig.isExcluded(relativePath);
    }

    /** 可用技能；永不为 null */
    public SkillRegistry skills() {
        return skills == null ? SkillRegistry.empty() : skills;
    }
}
