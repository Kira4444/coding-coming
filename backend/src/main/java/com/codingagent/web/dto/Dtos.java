package com.codingagent.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 请求/响应 DTO 集中在此，避免每条接口建一个文件把包撑爆。 */
public final class Dtos {

    private Dtos() {
    }

    // ---------- 项目 ----------

    public record CreateProjectRequest(
            @NotBlank String name,
            @NotBlank String repoType,   // GIT_URL / LOCAL_PATH
            String repoUrl,
            String localPath,
            String sandboxImage,
            String modelOverride,
            AgentConfigDto agent
    ) {
    }

    /** 项目设置更新：字段为 null 表示不改。 */
    public record UpdateProjectRequest(
            String name,
            String repoType,
            String repoUrl,
            String localPath,
            String sandboxImage,
            String modelOverride,
            AgentConfigDto agent
    ) {
    }

    public record ProjectResponse(
            Long id, String name, String repoType, String repoUrl,
            String localPath, String sandboxImage, String modelOverride,
            AgentConfigDto agent
    ) {
    }

    /**
     * 项目级 agent 行为配置。
     *
     * <p>单独成一个嵌套结构而不是把字段平铺在项目上：这一组字段是「怎么干活」，
     * 与「代码从哪来」属于不同关注点，前端也按两个面板分开呈现。
     */
    public record AgentConfigDto(
            String instructions,
            List<String> enabledTools,
            String approvalPolicy,
            Integer maxTurns,
            Long tokenBudget,
            List<String> excludePaths
    ) {
    }

    // ---------- 会话 ----------

    public record CreateSessionRequest(
            Long projectId,
            String title,
            String approvalPolicy   // 可空，默认 ASK_ON_WRITE
    ) {
    }

    public record SessionResponse(
            String id, Long projectId, String title, String status,
            String approvalPolicy, String branchName,
            boolean workspacePurged, long promptTokens, long completionTokens
    ) {
    }

    public record SendMessageRequest(@NotBlank String content) {
    }

    public record ApprovalRequest(@NotBlank String approvalId, boolean approved) {
    }

    /** SSE 事件统一包一层，前端按 type 分发 */
    public record EventEnvelope(String type, Object data, long ts) {
    }

    // ---------- diff ----------

    /** 会话改动的结构化 diff。比纯文本 diff 更好渲染。 */
    public record DiffResponse(
            String branch,
            String baseRef,
            List<FileDiff> files,
            int addedLines,
            int removedLines
    ) {
    }

    public record FileDiff(
            String path,
            String oldPath,
            // ADDED / MODIFIED / DELETED / RENAMED
            String changeType,
            int added,
            int removed,
            // unified diff 的 hunk 文本
            String patch
    ) {
    }
}
