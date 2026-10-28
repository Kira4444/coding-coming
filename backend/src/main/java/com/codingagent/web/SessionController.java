package com.codingagent.auth;

import com.codingagent.agent.AgentEvent;
import com.codingagent.storage.entity.Session;
import com.codingagent.web.RewindService;
import com.codingagent.web.SessionService;
import com.codingagent.web.dto.Dtos;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 会话生命周期与对话入口。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final RewindService rewindService;
    private final CurrentUser currentUser;

    public SessionController(SessionService sessionService, RewindService rewindService,
                             CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.rewindService = rewindService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public Mono<Dtos.SessionResponse> create(ServerHttpRequest request,
                                             @Valid @RequestBody Dtos.CreateSessionRequest req) {
        Long userId = currentUser.requireUserId(request);
        if (req.projectId() == null) {
            return Mono.error(new IllegalArgumentException("projectId 不能为空"));
        }
        return Mono.just(toResponse(sessionService.createSession(
                userId, req.projectId(), req.title(), req.approvalPolicy())));
    }

    @GetMapping
    public Mono<List<Dtos.SessionResponse>> list(ServerHttpRequest request,
                                                 @RequestParam(required = false) Long projectId) {
        Long userId = currentUser.requireUserId(request);
        List<Session> sessions = projectId != null
                ? sessionService.listSessionsByProject(projectId, userId)
                : sessionService.listSessions(userId);
        return Mono.just(sessions.stream().map(SessionController::toResponse).toList());
    }

    @GetMapping("/{id}")
    public Mono<Dtos.SessionResponse> get(ServerHttpRequest request, @PathVariable String id) {
        return Mono.just(toResponse(require(request, id)));
    }

    /**
     * 会话的全部消息行——<b>含已回滚</b>的（rolledBack=true 界面灰显）。
     * 发给模型的历史走另一条筛选（loadHistory 过滤 rolled_back=0），别互相复用。
     */
    @GetMapping("/{id}/messages")
    public Mono<List<MessageView>> messages(ServerHttpRequest request, @PathVariable String id) {
        require(request, id);
        return Mono.just(sessionService.listMessages(id).stream()
                .map(m -> new MessageView(
                        m.getId(), m.getRole(), m.getContent(), m.getReasoning(),
                        m.getToolCalls(), m.getToolCallId(), m.getToolName(), m.getTruncated(),
                        m.getTurn(), Boolean.TRUE.equals(m.getRolledBack())))
                .toList());
    }

    // ---------- rewind（回滚会话 + 代码） ----------

    /** 列出可回滚点。running=true 时前端只展示列表、禁用「回滚到此」（后端兜底 409）。 */
    @GetMapping("/{id}/rewind-points")
    public Mono<Dtos.RewindPointsResponse> rewindPoints(ServerHttpRequest request,
                                                        @PathVariable String id) {
        Session session = require(request, id);
        return Mono.fromCallable(() -> rewindService.rewindPoints(session));
    }

    /** 回滚到第 N 轮：保留 1..N，撤销 N+1 及之后（对话与代码一起回）。 */
    @PostMapping("/{id}/rewind")
    public Mono<Dtos.RewindOpView> rewind(ServerHttpRequest request,
                                          @PathVariable String id,
                                          @Valid @RequestBody Dtos.RewindRequest req) {
        Long userId = currentUser.requireUserId(request);
        Session session = require(request, id);
        return Mono.fromCallable(() -> rewindService.rewind(session, req, userId));
    }

    /** 回滚/恢复操作日志，按 id 降序。 */
    @GetMapping("/{id}/rewinds")
    public Mono<List<Dtos.RewindOpView>> rewinds(ServerHttpRequest request, @PathVariable String id) {
        Session session = require(request, id);
        return Mono.fromCallable(() -> rewindService.listOps(session));
    }

    /** 恢复 = 撤销 opId 这一步，回到它执行之前的状态（对 RECOVER 再调即重做）。 */
    @PostMapping("/{id}/rewinds/{opId}/recover")
    public Mono<Dtos.RewindOpView> recover(ServerHttpRequest request,
                                           @PathVariable String id,
                                           @PathVariable Long opId,
                                           @RequestBody(required = false) Dtos.RecoverRequest req) {
        Long userId = currentUser.requireUserId(request);
        Session session = require(request, id);
        return Mono.fromCallable(() -> rewindService.recover(session, opId, req, userId));
    }

    /** 会话分支上的改动 diff。 */
    @GetMapping("/{id}/diff")
    public Mono<Dtos.DiffResponse> diff(ServerHttpRequest request, @PathVariable String id) {
        require(request, id);
        return Mono.fromCallable(() -> sessionService.sessionDiff(id));
    }

    /** 下载 patch。 */
    @GetMapping(value = "/{id}/patch", produces = "text/x-patch")
    public Mono<String> patch(ServerHttpRequest request, @PathVariable String id) {
        require(request, id);
        return Mono.fromCallable(() -> sessionService.sessionPatch(id));
    }

    /**
     * 发送消息并订阅执行过程。
     *
     * <p>用 SSE 而不是 WebSocket：单向推送就够，用户输入走普通 POST。
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Dtos.EventEnvelope>> send(
            ServerHttpRequest request,
            @PathVariable String id,
            @Valid @RequestBody Dtos.SendMessageRequest req) {

        Long userId = currentUser.requireUserId(request);
        Session session = require(request, id);

        return sessionService.sendMessage(session, req.content(), userId)
                .map(ev -> ServerSentEvent.<Dtos.EventEnvelope>builder()
                        .event(eventName(ev))
                        .data(new Dtos.EventEnvelope(eventName(ev), ev, System.currentTimeMillis()))
                        .build())
                .concatWithValues(ServerSentEvent.<Dtos.EventEnvelope>builder()
                        .event("done")
                        .data(new Dtos.EventEnvelope("done", null, System.currentTimeMillis()))
                        .build());
    }

    @PostMapping("/{id}/approval")
    public Mono<ApprovalResult> approval(ServerHttpRequest request,
                                         @PathVariable String id,
                                         @Valid @RequestBody Dtos.ApprovalRequest req) {
        Long userId = currentUser.requireUserId(request);
        Session session = require(request, id);
        boolean resolved = sessionService.resolveApproval(session, req.approvalId(), req.approved(), userId);
        return Mono.just(new ApprovalResult(resolved));
    }

    /** 回收工作区，释放磁盘。历史记录保留。 */
    @PostMapping("/{id}/purge")
    public Mono<Dtos.SessionResponse> purge(ServerHttpRequest request, @PathVariable String id) {
        Long userId = currentUser.requireUserId(request);
        Session session = require(request, id);
        sessionService.purgeWorkspace(session.getId(), false);
        return Mono.just(toResponse(sessionService.getSession(id)));
    }

    private Session require(ServerHttpRequest request, String id) {
        Long userId = currentUser.requireUserId(request);
        Session s = sessionService.getSession(id);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return s;
    }

    private static Dtos.SessionResponse toResponse(Session s) {
        return new Dtos.SessionResponse(
                s.getId(), s.getProjectId(), s.getTitle(), s.getStatus(),
                s.getApprovalPolicy(), s.getBranchName(),
                Boolean.TRUE.equals(s.getWorkspacePurged()),
                s.getPromptTokens() == null ? 0L : s.getPromptTokens(),
                s.getCompletionTokens() == null ? 0L : s.getCompletionTokens());
    }

    private static String eventName(AgentEvent ev) {
        // 用 instanceof 而不是 switch 模式：编译目标是 Java 17，后者仍是 preview
        if (ev instanceof AgentEvent.TurnStarted) {
            return "turn_started";
        }
        if (ev instanceof AgentEvent.TextDelta) {
            return "text_delta";
        }
        if (ev instanceof AgentEvent.ReasoningDelta) {
            return "reasoning_delta";
        }
        if (ev instanceof AgentEvent.ToolCallRequested) {
            return "tool_call";
        }
        if (ev instanceof AgentEvent.ApprovalRequired) {
            return "approval_required";
        }
        if (ev instanceof AgentEvent.ApprovalResolved) {
            return "approval_resolved";
        }
        if (ev instanceof AgentEvent.ToolCallCompleted) {
            return "tool_completed";
        }
        if (ev instanceof AgentEvent.UsageUpdated) {
            return "usage";
        }
        if (ev instanceof AgentEvent.AssistantCommitted) {
            return "assistant_committed";
        }
        if (ev instanceof AgentEvent.TurnCompleted) {
            return "turn_completed";
        }
        if (ev instanceof AgentEvent.Failed) {
            return "failed";
        }
        return "unknown";
    }

    // ---------- 响应视图 ----------

    public record MessageView(
            Long id, String role, String content, String reasoning, String toolCalls,
            String toolCallId, String toolName, Boolean truncated,
            // 该行所属轮次（旧行为 null，视为第 0 轮）
            Integer turn,
            // true = 已回滚（界面灰显，不进模型历史）
            Boolean rolledBack
    ) {
    }

    public record ApprovalResult(boolean resolved) {
    }
}
