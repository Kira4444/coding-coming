package com.codingagent.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表。新增工具只需注册成 {@link Tool} bean，无需改 agent loop。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> registered) {
        for (Tool t : registered) {
            if (tools.put(t.name(), t) != null) {
                throw new IllegalStateException("工具名重复: " + t.name());
            }
        }
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 供报错信息里列出可用工具名 */
    public List<String> findNames() {
        return List.copyOf(tools.keySet());
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** 生成给模型看的 tools 列表 */
    public List<ChatToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ChatToolSpec(t.name(), t.description(), t.parametersSchema()))
                .toList();
    }

    public record ChatToolSpec(String name, String description, Map<String, Object> parameters) {
    }
}
