package com.codingagent.web;

import com.codingagent.auth.CurrentUser;
import com.codingagent.mcp.McpServerManager;
import com.codingagent.storage.entity.McpServer;
import com.codingagent.storage.entity.Skill;
import com.codingagent.storage.mapper.McpServerMapper;
import com.codingagent.storage.mapper.SkillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 项目扩展能力的管理接口：MCP 服务器与 Skills。
 *
 * <p>挂在 {@code /api/projects/{projectId}/…} 下——这两类东西归属项目，
 * 独立成顶层资源会让「这个项目配了什么」变成多处查询才能拼出来的图景。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectExtensionsController {

    private final SessionService sessionService;
    private final McpServerMapper mcpServerMapper;
    private final SkillMapper skillMapper;
    private final McpServerManager mcpServerManager;
    private final CurrentUser currentUser;

    public ProjectExtensionsController(SessionService sessionService,
                                       McpServerMapper mcpServerMapper,
                                       SkillMapper skillMapper,
                                       McpServerManager mcpServerManager,
                                       CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.mcpServerMapper = mcpServerMapper;
        this.skillMapper = skillMapper;
        this.mcpServerManager = mcpServerManager;
        this.currentUser = currentUser;
    }

    // ========== DTO ==========

    public record McpServerRequest(
            @NotBlank String name,
            String transport,
            String command,
            String args,
            String env,
            String url,
            Boolean enabled
    ) {
    }

    public record McpServerView(
            Long id, String name, String transport, String command, String args, String env,
            String url, Boolean enabled,
            Boolean lastCheckOk, String lastCheckMsg, Instant lastCheckAt
    ) {
    }

    public record SkillRequest(
            @NotBlank String name,
            String description,
            @NotBlank String content,
            Boolean enabled
    ) {
    }

    public record SkillView(Long id, String name, String description, String content, Boolean enabled) {
    }

    public record CheckResultView(boolean ok, String message, Instant at) {
    }

    // ========== MCP ==========

    @GetMapping("/mcp-servers")
    public Mono<List<McpServerView>> listMcp(ServerHttpRequest request, @PathVariable Long projectId) {
        requireProject(request, projectId);
        return Mono.just(mcpServerMapper.selectList(Wrappers.<McpServer>lambdaQuery()
                        .eq(McpServer::getProjectId, projectId)
                        .orderByAsc(McpServer::getName)).stream()
                .map(this::toView).toList());
    }

    @PostMapping("/mcp-servers")
    public Mono<McpServerView> createMcp(ServerHttpRequest request,
                                         @PathVariable Long projectId,
                                         @jakarta.validation.Valid @RequestBody McpServerRequest req) {
        requireProject(request, projectId);
        McpServer s = new McpServer();
        s.setProjectId(projectId);
        applyMcp(s, req);
        // 项目内名字唯一，冲突时报明确错误而不是让数据库抛
        Long dup = mcpServerMapper.selectCount(Wrappers.<McpServer>lambdaQuery()
                .eq(McpServer::getProjectId, projectId).eq(McpServer::getName, s.getName()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("该项目下已存在同名 MCP 服务器: " + s.getName());
        }
        mcpServerMapper.insert(s);
        return Mono.just(toView(s));
    }

    @PutMapping("/mcp-servers/{id}")
    public Mono<McpServerView> updateMcp(ServerHttpRequest request,
                                         @PathVariable Long projectId,
                                         @PathVariable Long id,
                                         @RequestBody McpServerRequest req) {
        requireProject(request, projectId);
        McpServer s = requireMcp(projectId, id);
        applyMcp(s, req);
        mcpServerMapper.updateById(s);
        return Mono.just(toView(s));
    }

    @DeleteMapping("/mcp-servers/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> deleteMcp(ServerHttpRequest request,
                                @PathVariable Long projectId, @PathVariable Long id) {
        requireProject(request, projectId);
        McpServer s = requireMcp(projectId, id);
        mcpServerMapper.deleteById(s.getId());
        return Mono.empty();
    }

    /**
     * 连通性探测。
     *
     * <p>会真的起进程握手、拉工具列表再关掉——只校验字段格式是自欺欺人，
     * 用户要的是「这个配置能不能用」。
     */
    @PostMapping("/mcp-servers/{id}/check")
    public Mono<CheckResultView> checkMcp(ServerHttpRequest request,
                                          @PathVariable Long projectId, @PathVariable Long id) {
        requireProject(request, projectId);
        McpServer s = requireMcp(projectId, id);
        return Mono.fromCallable(() -> {
            McpServerManager.CheckResult r = mcpServerManager.check(s);
            s.setLastCheckAt(r.at());
            s.setLastCheckOk(r.ok());
            s.setLastCheckMsg(r.message());
            mcpServerMapper.updateById(s);
            return new CheckResultView(r.ok(), r.message(), r.at());
        });
    }

    // ========== Skills ==========

    @GetMapping("/skills")
    public Mono<List<SkillView>> listSkills(ServerHttpRequest request, @PathVariable Long projectId) {
        requireProject(request, projectId);
        return Mono.just(skillMapper.selectList(Wrappers.<Skill>lambdaQuery()
                        .eq(Skill::getProjectId, projectId)
                        .orderByAsc(Skill::getName)).stream()
                .map(this::toView).toList());
    }

    @PostMapping("/skills")
    public Mono<SkillView> createSkill(ServerHttpRequest request,
                                       @PathVariable Long projectId,
                                       @jakarta.validation.Valid @RequestBody SkillRequest req) {
        requireProject(request, projectId);
        Skill s = new Skill();
        s.setProjectId(projectId);
        applySkill(s, req);
        Long dup = skillMapper.selectCount(Wrappers.<Skill>lambdaQuery()
                .eq(Skill::getProjectId, projectId).eq(Skill::getName, s.getName()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("该项目下已存在同名技能: " + s.getName());
        }
        skillMapper.insert(s);
        return Mono.just(toView(s));
    }

    @PutMapping("/skills/{id}")
    public Mono<SkillView> updateSkill(ServerHttpRequest request,
                                       @PathVariable Long projectId,
                                       @PathVariable Long id,
                                       @RequestBody SkillRequest req) {
        requireProject(request, projectId);
        Skill s = requireSkill(projectId, id);
        applySkill(s, req);
        skillMapper.updateById(s);
        return Mono.just(toView(s));
    }

    @DeleteMapping("/skills/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSkill(ServerHttpRequest request,
                                  @PathVariable Long projectId, @PathVariable Long id) {
        requireProject(request, projectId);
        Skill s = requireSkill(projectId, id);
        skillMapper.deleteById(s.getId());
        return Mono.empty();
    }

    // ========== 内部 ==========

    private void requireProject(ServerHttpRequest request, Long projectId) {
        Long userId = currentUser.requireUserId(request);
        sessionService.getProject(projectId, userId);
    }

    private McpServer requireMcp(Long projectId, Long id) {
        McpServer s = mcpServerMapper.selectById(id);
        if (s == null || !projectId.equals(s.getProjectId())) {
            throw new IllegalArgumentException("MCP 服务器不存在: " + id);
        }
        return s;
    }

    private Skill requireSkill(Long projectId, Long id) {
        Skill s = skillMapper.selectById(id);
        if (s == null || !projectId.equals(s.getProjectId())) {
            throw new IllegalArgumentException("技能不存在: " + id);
        }
        return s;
    }

    private void applyMcp(McpServer s, McpServerRequest req) {
        String name = req.name().trim();
        if (!name.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "MCP 服务器名只能是字母、数字、下划线、连字符，最长 64（用于工具命名空间）");
        }
        s.setName(name);
        s.setTransport(req.transport() == null || req.transport().isBlank()
                ? "STDIO" : req.transport().trim().toUpperCase());
        if (!"STDIO".equals(s.getTransport()) && !"SSE".equals(s.getTransport())
                && !"HTTP".equals(s.getTransport())) {
            throw new IllegalArgumentException("transport 只能是 STDIO / SSE / HTTP");
        }
        s.setCommand(req.command());
        s.setArgs(req.args());
        s.setEnv(req.env());
        s.setUrl(req.url());
        s.setEnabled(req.enabled() == null || req.enabled());

        if ("STDIO".equals(s.getTransport()) && isBlank(s.getCommand())) {
            throw new IllegalArgumentException("STDIO 类型必须提供 command");
        }
        if (!"STDIO".equals(s.getTransport()) && isBlank(s.getUrl())) {
            throw new IllegalArgumentException("SSE / HTTP 类型必须提供 url");
        }
    }

    private void applySkill(Skill s, SkillRequest req) {
        String name = req.name().trim();
        if (!name.matches("[a-zA-Z0-9_\\-一-龥]{1,64}")) {
            throw new IllegalArgumentException("技能名含非法字符或过长（最长 64）");
        }
        s.setName(name);
        s.setDescription(req.description());
        s.setContent(req.content());
        s.setEnabled(req.enabled() == null || req.enabled());
    }

    private McpServerView toView(McpServer s) {
        return new McpServerView(s.getId(), s.getName(), s.getTransport(), s.getCommand(),
                s.getArgs(), s.getEnv(), s.getUrl(), s.getEnabled(),
                s.getLastCheckOk(), s.getLastCheckMsg(), s.getLastCheckAt());
    }

    private SkillView toView(Skill s) {
        return new SkillView(s.getId(), s.getName(), s.getDescription(), s.getContent(), s.getEnabled());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
