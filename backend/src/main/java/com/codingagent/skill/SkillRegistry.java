package com.codingagent.skill;

import com.codingagent.storage.entity.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次执行期间可用的 skills。
 *
 * <p>从数据库读出后冻结在内存里：会话跑一半用户改了 skill 定义，
 * 不该让这一轮的上下文发生不可预期的漂移。下一轮重新加载。
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(List<Skill> enabled) {
        if (enabled != null) {
            for (Skill s : enabled) {
                if (Boolean.FALSE.equals(s.getEnabled())) {
                    continue;
                }
                if (s.getName() != null && !s.getName().isBlank()) {
                    skills.put(s.getName().trim(), s);
                }
            }
        }
    }

    public static SkillRegistry empty() {
        return new SkillRegistry(List.of());
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public int size() {
        return skills.size();
    }

    /**
     * 生成塞进系统提示的索引。
     *
     * <p>只放名字和一句话描述，不放正文——技能正文可能很长，
     * 全塞进去会把上下文吃穿，而大部分技能这一轮根本用不上。
     * agent 看到索引后按需调 {@code load_skill} 拉取正文。
     */
    public String indexText() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本项目提供 ").append(skills.size()).append(" 个可复用技能。\n");
        sb.append("需要某项技能的完整指引时，用 load_skill 工具按名字拉取，不要凭记忆猜测内容。\n\n");
        for (Skill s : skills.values()) {
            sb.append("- `").append(s.getName()).append("`");
            if (s.getDescription() != null && !s.getDescription().isBlank()) {
                sb.append(" —— ").append(s.getDescription().trim());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public List<String> names() {
        return new ArrayList<>(skills.keySet());
    }
}
