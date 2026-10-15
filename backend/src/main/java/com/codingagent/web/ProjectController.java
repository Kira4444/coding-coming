package com.codingagent.web;

import com.codingagent.agent.ProjectAgentConfig;
import com.codingagent.auth.CurrentUser;
import com.codingagent.storage.entity.Project;
import com.codingagent.web.dto.Dtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 项目管理。
 *
 * <p>身份一律走 {@link CurrentUser}，控制器里不再出现 {@code X-User-Id} 之类的头名。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final SessionService sessionService;
    private final CurrentUser currentUser;

    public ProjectController(SessionService sessionService, CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public Mono<Dtos.ProjectResponse> create(ServerHttpRequest request,
                                             @Valid @RequestBody Dtos.CreateProjectRequest req) {
        Long userId = currentUser.requireUserId(request);
        validateRepo(req.repoType(), req.repoUrl(), req.localPath());
        return Mono.just(toResponse(sessionService.createProject(
                userId, req.name(), req.repoType(), req.repoUrl(), req.localPath(),
                req.sandboxImage(), req.modelOverride(), req.agent())));
    }

    @GetMapping
    public Mono<List<Dtos.ProjectResponse>> list(ServerHttpRequest request) {
        Long userId = currentUser.requireUserId(request);
        return Mono.just(sessionService.listProjects(userId).stream()
                .map(ProjectController::toResponse).toList());
    }

    @GetMapping("/{id}")
    public Mono<Dtos.ProjectResponse> get(ServerHttpRequest request, @PathVariable Long id) {
        Long userId = currentUser.requireUserId(request);
        Project p = sessionService.getProject(id, userId);
        return Mono.just(toResponse(p));
    }

    /** 项目设置保存。详情页用。 */
    @PutMapping("/{id}")
    public Mono<Dtos.ProjectResponse> update(ServerHttpRequest request,
                                             @PathVariable Long id,
                                             @Valid @RequestBody Dtos.UpdateProjectRequest req) {
        Long userId = currentUser.requireUserId(request);
        if (req.repoType() != null) {
            validateRepo(req.repoType(), req.repoUrl(), req.localPath());
        }
        return Mono.just(toResponse(sessionService.updateProject(id, userId, req)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(ServerHttpRequest request, @PathVariable Long id) {
        Long userId = currentUser.requireUserId(request);
        sessionService.deleteProject(id, userId);
        return Mono.empty();
    }

    private static void validateRepo(String repoType, String repoUrl, String localPath) {
        if (!"GIT_URL".equals(repoType) && !"LOCAL_PATH".equals(repoType)) {
            throw new IllegalArgumentException("repoType 只能是 GIT_URL 或 LOCAL_PATH");
        }
        if ("GIT_URL".equals(repoType) && isBlank(repoUrl)) {
            throw new IllegalArgumentException("GIT_URL 项目必须提供 repoUrl");
        }
        if ("LOCAL_PATH".equals(repoType) && isBlank(localPath)) {
            throw new IllegalArgumentException("LOCAL_PATH 项目必须提供 localPath");
        }
    }

    private static Dtos.ProjectResponse toResponse(Project p) {
        return new Dtos.ProjectResponse(
                p.getId(), p.getName(), p.getRepoType(),
                p.getRepoUrl(), p.getLocalPath(), p.getSandboxImage(), p.getModelOverride(),
                toAgentDto(p));
    }

    /** 项目实体 → agent 配置 DTO */
    public static Dtos.AgentConfigDto toAgentDto(Project p) {
        return new Dtos.AgentConfigDto(
                p.getAgentInstructions(),
                List.copyOf(ProjectAgentConfig.parseList(p.getEnabledTools())),
                p.getApprovalPolicy(),
                p.getMaxTurns(),
                p.getTokenBudget(),
                List.copyOf(ProjectAgentConfig.parseList(p.getExcludePaths())));
    }

    /** agent 配置 DTO → 实体字段。传 null 表示不改。 */
    public static void applyAgentDto(Project p, Dtos.AgentConfigDto dto) {
        if (dto == null) {
            return;
        }
        if (dto.instructions() != null) {
            p.setAgentInstructions(dto.instructions().isBlank() ? null : dto.instructions());
        }
        if (dto.enabledTools() != null) {
            p.setEnabledTools(dto.enabledTools().isEmpty() ? null : String.join(",", dto.enabledTools()));
        }
        if (dto.approvalPolicy() != null) {
            p.setApprovalPolicy(dto.approvalPolicy().isBlank() ? null : dto.approvalPolicy().trim());
        }
        if (dto.maxTurns() != null) {
            p.setMaxTurns(dto.maxTurns() > 0 ? dto.maxTurns() : null);
        }
        if (dto.tokenBudget() != null) {
            p.setTokenBudget(dto.tokenBudget() > 0 ? dto.tokenBudget() : null);
        }
        if (dto.excludePaths() != null) {
            p.setExcludePaths(dto.excludePaths().isEmpty() ? null : String.join(",", dto.excludePaths()));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
