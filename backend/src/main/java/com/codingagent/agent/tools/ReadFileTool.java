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
import java.util.List;
import java.util.Map;

/**
 * 读文件，带行号与分页。
 *
 * <p>给行号是刚需：后续 edit 工具靠行号定位，也让模型能说「改第 42 行」。
 * 分页是因为大文件一次灌进上下文会直接顶掉预算。
 */
@Component
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区中的文件内容，返回带行号的文本。大文件请用 offset/limit 分页读取。"
                + "不要一次性读取超大文件，先用 grep 定位再精读。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "path", ToolSchemas.str("工作区内的相对路径"),
                "offset", ToolSchemas.integer("起始行号，从 1 开始，可选"),
                "limit", ToolSchemas.integer("读取行数上限，可选，默认由服务端限制")
        ), "path");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        return false; // 只读
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) throws IOException {
        String rel = args.path("path").asText();
        int offset = args.path("offset").asInt(1);
        int limit = args.path("limit").asInt(ctx.limits().fileReadMaxLines());

        Path root = Path.of(ctx.sandbox().workspacePath()).toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            return ToolResult.failure("路径越界，只能读取工作区内的文件: " + rel);
        }
        if (!Files.exists(target)) {
            return ToolResult.failure("文件不存在: " + rel);
        }
        if (Files.isDirectory(target)) {
            return ToolResult.failure("这是目录不是文件，请用 glob 或 ls 列出内容: " + rel);
        }
        if (Files.size(target) > 2_000_000) {
            return ToolResult.failure("文件超过 2MB，请改用 grep 定位后分页读取: " + rel);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure("读取失败（可能是二进制或编码不支持）: " + e.getMessage());
        }

        int total = lines.size();
        int from = Math.max(1, offset);
        int to = Math.min(total, from - 1 + Math.max(1, limit));
        if (from > total) {
            return ToolResult.success("[文件共 " + total + " 行，offset=" + from + " 超出范围]", false);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[文件共 ").append(total).append(" 行，显示 ").append(from).append('-').append(to).append("]\n");
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%6d\t%s%n", i, lines.get(i - 1)));
        }
        boolean more = to < total;
        if (more) {
            sb.append("…(还有 ").append(total - to).append(" 行未读，用 offset=").append(to + 1).append(" 继续)");
        }
        return ToolResult.success(sb.toString(), more);
    }
}
