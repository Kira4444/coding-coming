package com.codingagent.agent.tools;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则内容检索。
 *
 * <p>自己实现而不是 shell 出 {@code grep}：Windows 开发机没有 grep，
 * 而且跨平台行为一致能省掉一堆「在我机器上搜不到」的问题。
 */
@Component
public class GrepTool implements Tool {

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", ".venv", "vendor", ".idea");
    private static final long MAX_FILE_BYTES = 1_000_000;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在工作区中按正则搜索文件内容，返回 文件:行号:内容 的匹配列表。"
                + "这是定位代码的首选方式，比通读文件高效得多。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "pattern", ToolSchemas.str("Java 正则表达式"),
                "path", ToolSchemas.str("限定搜索的子目录或文件，可选"),
                "glob", ToolSchemas.str("文件名 glob 过滤，如 *.java，可选"),
                "case_insensitive", ToolSchemas.bool("忽略大小写，可选"),
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
        String glob = args.path("glob").asText("");
        boolean ci = args.path("case_insensitive").asBoolean(false);
        int max = args.path("max_results").asInt(ctx.limits().searchMaxResults());

        Pattern regex;
        try {
            regex = Pattern.compile(pattern, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("正则语法错误: " + e.getMessage());
        }

        Path root = Path.of(ctx.sandbox().workspacePath()).toAbsolutePath().normalize();
        Path base = sub.isEmpty() ? root : root.resolve(sub).normalize();
        if (!base.startsWith(root)) {
            return ToolResult.failure("路径越界: " + sub);
        }

        PathMatcher globMatcher = glob.isEmpty() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<String> hits = new ArrayList<>();

        class Walker extends SimpleFileVisitor<Path> {
            int scanned;

            boolean done() {
                return hits.size() >= max;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (done()) {
                    return FileVisitResult.TERMINATE;
                }
                if (dir.equals(base)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 项目配置的排除路径，按目录整体剪枝
                String rel = root.relativize(dir).toString().replace('\\', '/');
                return ctx.isExcluded(rel) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (done()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (ctx.isExcluded(rel)) {
                    return FileVisitResult.CONTINUE;
                }
                if (globMatcher != null
                        && !globMatcher.matches(file.getFileName())
                        && !globMatcher.matches(root.relativize(file))) {
                    return FileVisitResult.CONTINUE;
                }
                scanned++;
                scanFile(file, root, regex, hits, max);
                return FileVisitResult.CONTINUE;
            }
        }

        Walker walker = new Walker();
        Files.walkFileTree(base, walker);
        int scanned = walker.scanned;

        if (hits.isEmpty()) {
            return ToolResult.success("在 " + scanned + " 个文件中没有匹配 /" + pattern + "/ 的内容");
        }
        boolean truncated = hits.size() >= max;
        StringBuilder sb = new StringBuilder();
        sb.append("匹配 ").append(hits.size()).append(" 处");
        if (truncated) {
            sb.append("（已达上限 ").append(max).append("）");
        }
        sb.append(":\n");
        for (String h : hits) {
            sb.append(h).append('\n');
        }
        return ToolResult.success(sb.toString().stripTrailing(), truncated);
    }

    private static void scanFile(Path file, Path root, Pattern regex, List<String> hits, int max) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // 二进制或编码问题，跳过
        }
        String rel = root.relativize(file).toString().replace('\\', '/');
        for (int i = 0; i < lines.size() && hits.size() < max; i++) {
            String line = lines.get(i);
            Matcher m = regex.matcher(line);
            if (m.find()) {
                String shown = line.length() > 300 ? line.substring(0, 300) + "…" : line;
                hits.add(rel + ":" + (i + 1) + ":" + shown.stripTrailing());
            }
        }
    }
}
