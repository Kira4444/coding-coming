package com.codingagent.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具参数 schema 的小工具，避免每处手写嵌套 Map 出错。 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", List.of(required));
        m.put("additionalProperties", false);
        return m;
    }

    public static Map<String, Object> str(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    public static Map<String, Object> integer(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", description);
        return m;
    }

    public static Map<String, Object> bool(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "boolean");
        m.put("description", description);
        return m;
    }

    public static Map<String, Object> array(String description, Map<String, Object> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("description", description);
        m.put("items", items);
        return m;
    }
}
