package com.codingagent.agent.tools;

import com.codingagent.agent.Tool;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.codingagent.sandbox.SandboxSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提交当前改动。
 *
 * <p>MVP <b>只 commit 不 push</b>：push 涉及凭据托管与远端权限，是二期的事。
 * 每完成一个阶段提交一次，是会话分支可回溯的基础——用户拿到 patch 才能对上改动的来龙去脉。
 */
@Component
public class GitCommitTool implements Tool {

    @Override
    public String name() {
        return "git_commit";
    }

    @Override
    public String description() {
        return "把当前工作区改动提交到会话分支。message 要说清做了什么，不要写 \"update\" 这种无意义的话。"
                + "不要 push，推送由用户自己操作。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolSchemas.object(Map.of(
                "message", ToolSchemas.str("提交说明，一句话概括本次改动"),
                "allow_empty", ToolSchemas.bool("允许空提交，默认 false")
        ), "message");
    }

    @Override
    public boolean requiresApproval(JsonNode args) {
        // commit 只影响会话自己的分支，且可 reset；交给 session 策略控制即可
        return false;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        String message = args.path("message").asText("");
        boolean allowEmpty = args.path("allow_empty").asBoolean(false);
        if (message.isBlank()) {
            return ToolResult.failure("message 不能为空");
        }
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            return ToolResult.failure("message 请用单行文字，不要换行");
        }

        SandboxSession sb = ctx.sandbox();
        String escaped = message.replace("'", "'\\''");

        SandboxSession.ExecResult status = sb.exec("git status --porcelain", 30);
        if (status.exitCode() != 0) {
            return ToolResult.failure("git status 失败，请确认工作区是 git 仓库: " + status.combined());
        }
        boolean empty = status.stdout() == null || status.stdout().isBlank();

        if (empty && !allowEmpty) {
            return ToolResult.failure("没有可提交的改动。如果确实要提交，请传 allow_empty=true。");
        }

        if (!empty) {
            SandboxSession.ExecResult add = sb.exec("git add -A", 60);
            if (add.exitCode() != 0) {
                return ToolResult.failure("git add 失败: " + add.combined());
            }
        }

        String cmd = allowEmpty && empty
                ? "git commit --allow-empty -m '" + escaped + "'"
                : "git commit -m '" + escaped + "'";
        SandboxSession.ExecResult commit = sb.exec(cmd, 60);
        if (commit.exitCode() != 0) {
            return ToolResult.failure("git commit 失败: " + commit.combined());
        }

        SandboxSession.ExecResult head = sb.exec("git log -1 --oneline", 30);
        return ToolResult.success("已提交:\n" + (head.stdout() == null ? commit.combined() : head.stdout()));
    }
}
