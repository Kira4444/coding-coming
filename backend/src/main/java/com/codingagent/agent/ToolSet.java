package com.codingagent.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次执行期间可见的工具全集 = 内置工具 + 会话级动态工具（MCP）。
 *
 * <p>动态工具名带命名空间（{@code mcp__*}），与内置工具冲突时
 * <b>动态工具让位</b>——内置工具是产品自己的能力边界，不该被外部服务器覆盖。
 */
public class ToolSet {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolSet(ToolRegistry registry, Collection<? extends Tool> extraTools) {
        // 内置优先占位
        for (Tool t : registry.all()) {
            byName.put(t.name(), t);
        }
        if (extraTools != null) {
            for (Tool t : extraTools) {
                if (byName.containsKey(t.name())) {
                    continue;
                }
                byName.put(t.name(), t);
            }
        }
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<Tool> all() {
        return byName.values();
    }

    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    /** 生成给模型看的 tools 列表 */
    public List<ChatToolSpec> specs() {
        List<ChatToolSpec> out = new ArrayList<>(byName.size());
        for (Tool t : byName.values()) {
            out.add(new ChatToolSpec(t.name(), t.description(), t.parametersSchema()));
        }
        return out;
    }

    public record ChatToolSpec(String name, String description, Map<String, Object> parameters) {
    }
}
