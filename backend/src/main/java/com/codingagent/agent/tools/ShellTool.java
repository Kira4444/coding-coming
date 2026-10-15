package com.codingagent.agent.tools;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.codingagent.sandbox.SandboxSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 执行 shell 命令。
 *
 * <p>这里是审批分级最关键的一环：只读命令自动放行，写/危险命令要人工确认。
 * 判定走「命令前缀白名单 + 危险模式黑名单」双层，两边都不命中时按写操作处理——
 * 宁可多拦一次，不可漏放一次。
 */
@Component
public class ShellTool implements Tool {

    /**
     * 只读命令白名单。判定看的是命令行里的首个词（含管道/&& 的第一段），
     * 不做完整 shell 语法解析——完整解析既做不到也防不住伪造。
     */
    private static final List<String> READ_ONLY_PREFIXES = List.of(
            "ls", "pwd", "whoami", "id", "uname", "hostname", "date",
            "cat", "head", "tail", "less", "more", "wc", "file", "stat", "du", "df",
            "echo", "printf", "which", "whereis", "type",
            "grep", "rg", "find", "fd", "tree", "locate",
            "git", "ps", "top", "htop", "free", "uptime",
            "node", "npm", "npx", "python", "python3", "pip", "pnpm", "yarn",
            "mvn", "gradle", "java", "javac", "go", "cargo", "dotnet", "make",
            "jq", "env", "printenv", "basename", "dirname", "realpath",
            "sort", "uniq", "cut", "tr", "sed", "awk", "diff", "cmp", "md5sum", "sha256sum");

    /** 这些命令即便出现在管道中段，也一律要确认 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(^|[\\s;&|])\\s*(rm|mv|dd|mkfs|shutdown|reboot|halt|kill|killall|pkill"
                    + "|chmod|chown|chgrp|curl|wget|nc|ncat|netcat|ssh|scp|rsync"
                    + "|docker|kubectl|systemctl|service|crontab|mount|umount"
                    + "|sudo|su|passwd|useradd|userdel|usermod|shutdown)\\b"
                    + "|\\brm\\s+(-[a-zA-Z]*\\s+)*-?[rf]{1,2}\\b"
                    + "|>[^&|]*\\.(env|pem|key|password|credentials)"
                    + "|\\bhistory\\s+-c\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 重定向到敏感位置一律拦。
     *
     * <p>覆盖两类：系统/家目录，以及密钥类文件名。
     * {@code /tmp} 与相对路径放行——否则连 {@code npm test > /tmp/out.log} 都要确认，会把人逼疯。
     */
    private static final Pattern SENSITIVE_REDIRECT = Pattern.compile(
            "(>>?|tee)\\s*[^\\s;|]*("
                    + "/(etc|usr|bin|sbin|boot|root|var|sys|proc)/"
                    + "|~/|\\$HOME/"
                    + "|\\.env\\b|\\.pem\\b|\\.key\\b|\\.password\\b"
                    + "|credentials|authorized_keys|id_rsa|id_ed25519|known_hosts"
                    + ")",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "在工作区中执行 shell 命令（跑测试、构建、安装依赖等）。命令在工作区根目录下执行。"
                + "只读命令自动执行；会修改文件或系统的命令需要人工确认。"
                + "测试失败时请把错误信息读清楚再改代码，不要盲目重试。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "command", ToolSchemas.str("要执行的 shell 命令"),
                "timeout_seconds", ToolSchemas.integer("超时秒数，可选，默认 120，最大 600")
        ), "command");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        String command = args.path("command").asText("");
        return !isReadOnly(command);
    }

    /**
     * 只读判定。刻意保守：看不准就当作写操作。
     */
    public static boolean isReadOnly(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        String trimmed = command.trim();
        if (DANGEROUS.matcher(trimmed).find()) {
            return false;
        }
        if (SENSITIVE_REDIRECT.matcher(trimmed).find()) {
            return false;
        }
        // 拆出第一段命令（按 shell 元字符）
        String first = trimmed.split("[;&|\\n\\r]", 2)[0].trim();
        if (first.isEmpty()) {
            return true;
        }
        String[] parts = first.split("\\s+");
        // 第一个词才是命令本身，后续都是参数
        String binary = parts[0];
        int slash = binary.lastIndexOf('/');
        if (slash >= 0) {
            binary = binary.substring(slash + 1);
        }
        int backslash = binary.lastIndexOf('\\');
        if (backslash >= 0) {
            binary = binary.substring(backslash + 1);
        }
        if (binary.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            binary = binary.substring(0, binary.length() - 4);
        }
        binary = binary.toLowerCase(Locale.ROOT);

        // git 单独看子命令：status/diff/log 只读，commit/reset/push 一律确认
        if ("git".equals(binary)) {
            return isReadOnlyGitCommand(parts);
        }
        // npm/yarn/pnpm 同理
        if ("npm".equals(binary) || "yarn".equals(binary) || "pnpm".equals(binary)) {
            return isReadOnlyPackageManager(parts);
        }
        // mvn/gradle：test/compile 只读，install/deploy 要确认
        if ("mvn".equals(binary) || "gradle".equals(binary)) {
            return isReadOnlyBuildTool(parts);
        }
        return READ_ONLY_PREFIXES.contains(binary);
    }

    private static boolean isReadOnlyGitCommand(String[] parts) {
        if (parts.length < 2) {
            return true;
        }
        // 跳过 git 的全局 flag
        String sub = null;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].startsWith("-")) {
                sub = parts[i].toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (sub == null) {
            return true;
        }
        return switch (sub) {
            case "status", "diff", "log", "show", "ls", "ls-files", "branch", "tag",
                 "remote", "rev", "rev-parse", "blame", "shortlog", "describe",
                 "config", "help", "version", "stash" -> true;
            default -> false;
        };
    }

    private static boolean isReadOnlyPackageManager(String[] parts) {
        if (parts.length < 2) {
            return true;
        }
        String sub = null;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].startsWith("-")) {
                sub = parts[i].toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (sub == null) {
            return true;
        }
        return switch (sub) {
            case "ls", "list", "view", "info", "outdated", "audit", "ping", "help",
                 "test", "run", "exec" -> true;
            default -> false;
        };
    }

    private static boolean isReadOnlyBuildTool(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].toLowerCase(Locale.ROOT);
            if (p.startsWith("-") || p.startsWith("/")) {
                continue;
            }
            return switch (p) {
                case "test", "tests", "check", "compile", "verify", "assemble", "tasks",
                     "dependencies", "dependency", "help", "version" -> true;
                default -> false;
            };
        }
        return true;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            return ToolResult.failure("command 不能为空");
        }
        int timeout = args.path("timeout_seconds").asInt(120);
        timeout = Math.max(1, Math.min(600, timeout));

        SandboxSession.ExecResult r = ctx.sandbox().exec(command, timeout);
        StringBuilder sb = new StringBuilder();
        sb.append("exit_code=").append(r.exitCode());
        if (r.timedOut()) {
            sb.append(" (超时)");
        }
        sb.append('\n').append(r.combined());

        int maxLines = ctx.limits().shellOutputMaxLines();
        int maxChars = ctx.limits().shellOutputMaxChars();
        String body = sb.toString();
        String output = truncate(body, maxLines, maxChars);

        if (r.exitCode() != 0) {
            return new ToolResult(false, output, r.truncated() || output.length() < body.length());
        }
        return new ToolResult(true, output, r.truncated() || output.length() < body.length());
    }

    static String truncate(String text, int maxLines, int maxChars) {
        String[] lines = text.split("\n", -1);
        boolean lineTrimmed = lines.length > maxLines;
        String result = text;
        if (lineTrimmed) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("…[输出过长，已截断 ").append(lines.length - maxLines).append(" 行，"
                    + "请改用更精确的命令如 `tail -n 50`]");
            result = sb.toString();
        }
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n…[输出过长，已按字符数截断]";
        }
        return result;
    }
}
