package com.codingagent.agent.tools;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 写文件。
 *
 * <p>主路径是 <b>search/replace 块</b>：模型给出 {@code old_text} 与 {@code new_text}，
 * 服务端做精确替换。这套方案对中等能力的模型（DeepSeek / GLM）最稳——
 * 不需要模型记行号，也不需要它重写整份文件因而漏改。
 *
 * <p>替换失败时降级为整文件重写（{@code content} 字段），并把失败原因回给模型让它修正。
 */
@Component
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "写入或修改工作区文件。优先使用 edits 字段做精确的 search/replace 替换"
                + "（old_text 必须在文件中唯一匹配）；仅当创建新文件或替换失效时才用 content 整文件写入。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> editItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "old_text", ToolSchemas.str("要被替换的原文，必须在文件中唯一出现"),
                        "new_text", ToolSchemas.str("替换后的文本")
                ),
                "required", List.of("new_text"),
                "additionalProperties", false);

        return ToolSchemas.object(Map.of(
                "path", ToolSchemas.str("工作区内的相对路径"),
                "edits", ToolSchemas.array("精确替换列表，按顺序应用", editItem),
                "content", ToolSchemas.str("整文件内容；创建新文件或 edits 失败时使用"),
                "create_dirs", ToolSchemas.bool("父目录不存在时是否创建，默认 true")
        ), "path");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        // 文件写默认自动；session 策略切到 always-ask 时由 agent loop 兜底拦截
        return false;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) throws IOException {
        String rel = args.path("path").asText();
        Path root = Path.of(ctx.sandbox().workspacePath()).toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            return ToolResult.failure("路径越界，只能写入工作区内的文件: " + rel);
        }

        boolean exists = Files.exists(target);
        JsonNode edits = args.get("edits");
        JsonNode content = args.get("content");
        boolean createDirs = args.path("create_dirs").asBoolean(true);

        if ((edits == null || !edits.isArray() || edits.isEmpty())
                && (content == null || content.isNull())) {
            return ToolResult.failure("必须提供 edits 或 content 之一");
        }

        // —— 主路径：search/replace ——
        if (edits != null && edits.isArray() && !edits.isEmpty()) {
            if (!exists) {
                return ToolResult.failure("文件不存在，无法应用 edits；创建新文件请用 content 字段: " + rel);
            }
            String original = Files.readString(target, StandardCharsets.UTF_8);
            String current = original;
            List<String> applied = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            for (int i = 0; i < edits.size(); i++) {
                JsonNode e = edits.get(i);
                String oldText = e.path("old_text").asText(null);
                String newText = e.path("new_text").asText("");
                if (oldText == null || oldText.isEmpty()) {
                    // 允许空 old_text 表示追加
                    current = current + (current.endsWith("\n") || current.isEmpty() ? "" : "\n") + newText;
                    applied.add("#" + i + " 追加到文件末尾");
                    continue;
                }
                int occurrences = countOccurrences(current, oldText);
                if (occurrences == 0) {
                    failures.add("#" + i + " 未找到匹配的 old_text");
                } else if (occurrences > 1) {
                    failures.add("#" + i + " old_text 匹配到 " + occurrences + " 处，必须唯一");
                } else {
                    current = replaceOnce(current, oldText, newText);
                    applied.add("#" + i + " 已替换");
                }
            }

            if (!failures.isEmpty()) {
                // 不做部分写入：部分成功会让文件处于模型没预期的状态，更难调试
                return ToolResult.failure("替换失败，文件未被修改: " + String.join("; ", failures)
                        + "。请核对 old_text 是否与文件内容完全一致（含缩进与空白），或改用 content 整文件写入。");
            }

            if (createDirs && target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, current, StandardCharsets.UTF_8);
            return ToolResult.success("已更新 " + rel + "，应用 " + applied.size() + " 处修改: "
                    + String.join(", ", applied));
        }

        // —— 降级路径：整文件写入 ——
        if (createDirs && target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content.asText(), StandardCharsets.UTF_8);
        return ToolResult.success((exists ? "已覆盖 " : "已创建 ") + rel
                + "（" + content.asText().length() + " 字符）");
    }

    /** 统计不重叠出现次数 */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceOnce(String text, String oldText, String newText) {
        int idx = text.indexOf(oldText);
        if (idx < 0) {
            return text;
        }
        return text.substring(0, idx) + newText + text.substring(idx + oldText.length());
    }
}
