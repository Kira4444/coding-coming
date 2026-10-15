package com.codingagent.skill;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.codingagent.agent.tools.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按需加载技能正文。
 *
 * <p>技能正文不进系统提示，只在 agent 明确需要时拉取——
 * 这是「索引 + 按需加载」而不是「全量注入」，上下文预算才花得值。
 */
@Component
public class LoadSkillTool implements Tool {

    /** 技能注册表由 agent loop 按项目装配后塞进 ToolContext */
    public static final String NAME = "load_skill";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按名字加载一个技能的完整指引。先用系统提示里列出的技能名，不要猜。"
                + "加载后请严格按该指引执行。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "name", ToolSchemas.str("技能名，见系统提示里的技能索引")
        ), "name");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        return false; // 只是读一段文本
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            return ToolResult.failure("必须指定技能名。可用技能: "
                    + String.join(", ", ctx.skills().names()));
        }
        return ctx.skills().find(name)
                .map(s -> ToolResult.success(
                        "## 技能 [" + s.getName() + "]\n\n" + s.getContent()))
                .orElseGet(() -> ToolResult.failure(
                        "技能 [" + name + "] 不存在或未启用。可用技能: "
                                + String.join(", ", ctx.skills().names())));
    }

    /** 供调试查看注册了哪些技能 */
    public static List<String> namesOf(SkillRegistry registry) {
        return registry.names();
    }
}
