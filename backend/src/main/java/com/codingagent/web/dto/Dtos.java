package com.codingagent.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
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

    // ---------- rewind（回滚会话 + 代码） ----------

    /** 回滚请求。turn 必填（0 = 会话初始状态）；expectedTipTurn 可选，并发防护。 */
    public record RewindRequest(Integer turn, Integer expectedTipTurn) {
    }

    /** 恢复请求，整个 body 可省略。expectedTipTurn 同上。 */
    public record RecoverRequest(Integer expectedTipTurn) {
    }

    /**
     * 一个可回滚点 = 某轮结束时的检查点。
     *
     * <p>points 按 turn 降序；turn=0 恒在最后，其 prompt/assistantSummary 为 null，
     * 界面显示固定文案「会话初始状态」（文案在前端本地化，后端不发 label）。
     */
    public record RewindPointView(
            int turn,
            // 该轮所属 run 的用户输入（截断 200 字符）；turn=0 为 null
            String prompt,
            // 该轮 ASSISTANT 行内容（截断 200 字符）；工具轮可能为空串，原样给
            String assistantSummary,
            int messageCount,
            String headSha,
            String shortSha,
            Instant createdAt,
            // false = 整轮在「已回滚」区
            boolean active
    ) {
    }

    /** 列出可回滚点。running=true 时前端只展示列表、禁用「回滚到此」。 */
    public record RewindPointsResponse(
            int currentTurn,
            boolean running,
            boolean workspacePurged,
            List<RewindPointView> points
    ) {
    }

    /**
     * 一次回滚/恢复操作。GET /rewinds 与两个操作端点返回同一形状，
     * 前端由此找「可恢复的操作」：状态为 APPLIED 且 id 最大者。
     */
    public record RewindOpView(
            Long opId,
            String kind,           // REWIND / RECOVER
            Integer fromTurn,
            Integer toTurn,
            Long undoneOpId,
            List<Integer> rolledTurns,
            List<Integer> unrolledTurns,
            int rolledMessageCount,
            String backupRef,
            String status,         // PENDING / APPLIED / RESTORED / FAILED
            Instant createdAt
    ) {
    }
}
