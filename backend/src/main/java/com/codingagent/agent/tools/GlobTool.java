package com.codingagent.agent.tools;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 按 glob 模式找文件。
 *
 * <p>这是 agent「看懂仓库」的主入口之一（另一条是 grep）。刻意不做向量索引——
 * 工具检索路径已被验证可靠，索引带来的复杂度在 MVP 不划算。
 */
@Component
public class GlobTool implements Tool {

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", ".venv", "vendor", ".idea");

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按 glob 模式查找文件，返回匹配的相对路径列表。例如 \"**/*.java\"、\"src/**/*.ts\"。"
                + "已自动跳过 node_modules、.git、target 等目录。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "pattern", ToolSchemas.str("glob 模式，如 **/*.java"),
                "path", ToolSchemas.str("限定搜索的子目录，可选，默认整个工作区"),
                "max_results", ToolSchemas.integer("最大返回条数，可选")
        ), "pattern");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        return false;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) throws IOException {
        String pattern = args.path("pattern").asText();
        String sub = args.path("path").asText("");
        int max = args.path("max_results").asInt(ctx.limits().searchMaxResults());

        Path root = Path.of(ctx.sandbox().workspacePath()).toAbsolutePath().normalize();
        Path base = sub.isEmpty() ? root : root.resolve(sub).normalize();
        if (!base.startsWith(root) || !Files.isDirectory(base)) {
            return ToolResult.failure("搜索目录无效: " + sub);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> hits = new ArrayList<>();

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (hits.size() >= max) {
                    return FileVisitResult.TERMINATE;
                }
                if (dir.equals(base)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (hits.size() >= max) {
                    return FileVisitResult.TERMINATE;
                }
                Path rel = root.relativize(file);
                String relStr = rel.toString().replace('\\', '/');
                // 项目配置的排除路径优先于 glob 匹配
                if (ctx.isExcluded(relStr)) {
                    return FileVisitResult.CONTINUE;
                }
                if (matcher.matches(rel) || matcher.matches(file.getFileName())) {
                    hits.add(relStr);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(hits);
        if (hits.isEmpty()) {
            return ToolResult.success("没有匹配 " + pattern + " 的文件");
        }
        boolean truncated = hits.size() >= max;
        StringBuilder sb = new StringBuilder();
        sb.append("匹配 ").append(hits.size()).append(" 个文件");
        if (truncated) {
            sb.append("（已达上限 ").append(max).append("）");
        }
        sb.append(":\n");
        for (String h : hits) {
            sb.append(h).append('\n');
        }
        return ToolResult.success(sb.toString().stripTrailing(), truncated);
    }
}
