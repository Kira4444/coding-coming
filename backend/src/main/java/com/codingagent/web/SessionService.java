package com.codingagent.web;

import com.codingagent.agent.AgentEvent;
import com.codingagent.agent.AgentLimits;
import com.codingagent.agent.AgentLoop;
import com.codingagent.agent.AgentPropertiesMapper;
import com.codingagent.agent.ApprovalHandler;
import com.codingagent.agent.SessionRuntime;
import com.codingagent.agent.SystemPrompt;
import com.codingagent.config.AgentProperties;
import com.codingagent.config.SandboxProperties;
import com.codingagent.config.WorkspaceProperties;
import com.codingagent.git.GitSnapshotService;
import com.codingagent.model.ChatMessage;
import com.codingagent.model.ToolCall;
import com.codingagent.mcp.McpServerManager;
import com.codingagent.sandbox.SandboxDriver;
import com.codingagent.sandbox.SandboxSession;
import com.codingagent.storage.entity.McpServer;
import com.codingagent.storage.entity.Message;
import com.codingagent.storage.entity.Project;
import com.codingagent.storage.entity.Session;
import com.codingagent.storage.entity.Skill;
import com.codingagent.storage.entity.TurnCheckpoint;
import com.codingagent.storage.mapper.AuditLogMapper;
import com.codingagent.storage.mapper.MessageMapper;
import com.codingagent.storage.mapper.ProjectMapper;
import com.codingagent.storage.mapper.SessionMapper;
import com.codingagent.storage.mapper.SessionRewindMapper;
import com.codingagent.storage.mapper.TurnCheckpointMapper;
import com.codingagent.web.dto.Dtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话编排：建工作区、跑 agent、落库、审计。
 *
 * <p>这个类是「业务缝合层」，刻意不做算法——它只负责让各层按顺序各就各位。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionMapper sessionMapper;
    private final ProjectMapper projectMapper;
    private final MessageMapper messageMapper;
    private final AuditLogMapper auditLogMapper;
    private final TurnCheckpointMapper turnCheckpointMapper;
    private final SessionRewindMapper sessionRewindMapper;
    private final SandboxDriver sandboxDriver;
    private final AgentLoop agentLoop;
    private final ObjectMapper mapper;

    private final AgentProperties agentProperties;
    private final SandboxProperties sandboxProperties;
    private final WorkspaceProperties workspaceProperties;
    private final GitDiffService gitDiffService;
    private final WorkspaceInitializer workspaceInitializer;
    private final GitSnapshotService gitSnapshotService;
    private final com.codingagent.storage.mapper.McpServerMapper mcpServerMapper;
    private final com.codingagent.storage.mapper.SkillMapper skillMapper;
    private final com.codingagent.mcp.McpServerManager mcpServerManager;

    /** 活着的会话。进程重启后为空，靠 {@link #activate} 重建。 */
    private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 会话执行闸门——回滚/恢复与 agent 运行靠它互斥。
     *
     * <p>刻意<b>不</b>放进 {@link SessionRuntime}：那个对象要等 {@link #activate}
     * 才建出来，而闸门必须在「尚未激活」时就可用。否则重启后第一次操作会出现
     * 「回滚拿到空锁放行、sendMessage 紧接着建出 runtime 开跑」的竞态，
     * 两边同时改同一份工作区。
     *
     * <p>{@code computeIfAbsent} 保证无论谁先到，拿到的都是同一把真实存在的锁。
     *
     * <p>条目<b>永不移除</b>：移除会重新引入上面那个竞态（放行方拿的是新建的空锁）。
     * 每个会话一个 {@link AtomicBoolean}，代价可忽略。
     */
    private final Map<String, AtomicBoolean> runGates = new ConcurrentHashMap<>();

    /** 取会话闸门。不存在则原子创建——这是互斥能成立的关键。 */
    private AtomicBoolean gateOf(String sessionId) {
        return runGates.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
    }

    /** 并发闸门：限制同时跑的 agent 数，超出的在队列里等 */
    private final AtomicInteger activeAgents = new AtomicInteger(0);

    public SessionService(
            SessionMapper sessionMapper,
            ProjectMapper projectMapper,
            MessageMapper messageMapper,
            AuditLogMapper auditLogMapper,
            TurnCheckpointMapper turnCheckpointMapper,
            SessionRewindMapper sessionRewindMapper,
            SandboxDriver sandboxDriver,
            AgentLoop agentLoop,
            ObjectMapper mapper,
            AgentProperties agentProperties,
            SandboxProperties sandboxProperties,
            WorkspaceProperties workspaceProperties,
            GitDiffService gitDiffService,
            WorkspaceInitializer workspaceInitializer,
            GitSnapshotService gitSnapshotService,
            com.codingagent.storage.mapper.McpServerMapper mcpServerMapper,
            com.codingagent.storage.mapper.SkillMapper skillMapper,
            com.codingagent.mcp.McpServerManager mcpServerManager) {
        this.sessionMapper = sessionMapper;
        this.projectMapper = projectMapper;
        this.messageMapper = messageMapper;
        this.auditLogMapper = auditLogMapper;
        this.turnCheckpointMapper = turnCheckpointMapper;
        this.sessionRewindMapper = sessionRewindMapper;
        this.sandboxDriver = sandboxDriver;
        this.agentLoop = agentLoop;
        this.mapper = mapper;
        this.agentProperties = agentProperties;
        this.sandboxProperties = sandboxProperties;
        this.workspaceProperties = workspaceProperties;
        this.gitDiffService = gitDiffService;
        this.workspaceInitializer = workspaceInitializer;
        this.gitSnapshotService = gitSnapshotService;
        this.mcpServerMapper = mcpServerMapper;
        this.skillMapper = skillMapper;
        this.mcpServerManager = mcpServerManager;
    }

    // ---------- 项目 ----------

    public Project createProject(Long ownerId, String name, String repoType,
                                 String repoUrl, String localPath,
                                 String sandboxImage, String modelOverride,
                                 Dtos.AgentConfigDto agent) {
        validateRepoSource(repoType, repoUrl, localPath);
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setRepoType(repoType);
        p.setRepoUrl(repoUrl);
        p.setLocalPath(localPath);
        p.setSandboxImage(sandboxImage);
        p.setModelOverride(modelOverride);
        com.codingagent.web.ProjectController.applyAgentDto(p, agent);
        projectMapper.insert(p);
        return p;
    }

    /**
     * 建项目时就校验代码来源。
     *
     * <p>早期失败比晚期失败便宜得多：路径填错要等到建会话才报，
     * 用户会以为是服务坏了（我们确实先返回了「服务内部错误」）。
     */
    private void validateRepoSource(String repoType, String repoUrl, String localPath) {
        if ("LOCAL_PATH".equals(repoType)) {
            if (localPath == null || localPath.isBlank()) {
                throw new IllegalArgumentException("请填服务器本地路径");
            }
            Path p = Path.of(localPath.trim());
            if (!Files.isDirectory(p)) {
                throw new IllegalArgumentException(
                        "本地路径不存在或不是目录: " + localPath.trim()
                                + "。请填 agent 所在机器上的绝对路径，例如 C:/Users/you/code/my-service");
            }
        } else if ("GIT_URL".equals(repoType)) {
            if (repoUrl == null || repoUrl.isBlank()) {
                throw new IllegalArgumentException("请填 Git 仓库地址");
            }
            String u = repoUrl.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")
                    && !u.startsWith("git@") && !u.startsWith("ssh://")) {
                throw new IllegalArgumentException(
                        "Git 地址格式不对: " + u + "。应以 http(s):// 或 git@ 或 ssh:// 开头");
            }
        }
    }

    public List<Project> listProjects(Long ownerId) {
        return projectMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Project>lambdaQuery()
                        .eq(Project::getOwnerId, ownerId)
                        .orderByDesc(Project::getId));
    }

    /** 取项目并校验归属。越权访问直接判不存在，不泄露「这个 id 存在但不是你的」。 */
    public Project getProject(Long projectId, Long userId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null || !p.getOwnerId().equals(userId)) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        return p;
    }

    public Project updateProject(Long projectId, Long userId, com.codingagent.web.dto.Dtos.UpdateProjectRequest req) {
        Project p = getProject(projectId, userId);
        if (req.name() != null && !req.name().isBlank()) {
            p.setName(req.name().trim());
        }
        if (req.repoType() != null) {
            p.setRepoType(req.repoType());
        }
        if (req.repoUrl() != null) {
            p.setRepoUrl(req.repoUrl().isBlank() ? null : req.repoUrl().trim());
        }
        if (req.localPath() != null) {
            p.setLocalPath(req.localPath().isBlank() ? null : req.localPath().trim());
        }
        if (req.sandboxImage() != null) {
            p.setSandboxImage(req.sandboxImage().isBlank() ? null : req.sandboxImage().trim());
        }
        if (req.modelOverride() != null) {
            p.setModelOverride(req.modelOverride().isBlank() ? null : req.modelOverride().trim());
        }
        com.codingagent.web.ProjectController.applyAgentDto(p, req.agent());
        // 更新同样要校验——改了路径不验证等于没验
        validateRepoSource(p.getRepoType(), p.getRepoUrl(), p.getLocalPath());
        projectMapper.updateById(p);
        return p;
    }

    /** 持久化发布配置。调用方已改好实体字段。 */
    public void updateProjectPublishConfig(Project p) {
        projectMapper.updateById(p);
    }

    /** 发布动作留痕——发布是对外动作，必须能追溯是谁把什么推到了哪儿 */
    public void auditPublish(Session s, Project p, boolean ok, String message) {
        audit(s.getId(), s.getUserId(), "PUBLISH", "git_push",
                "target=" + p.getPublishRemote() + "/" + p.getPublishBranch()
                        + " ok=" + ok + " " + message, ok);
    }

    /** 把项目实体折算成 agent 可用的配置 */
    public com.codingagent.agent.ProjectAgentConfig agentConfigOf(Project p) {
        if (p == null) {
            return com.codingagent.agent.ProjectAgentConfig.DEFAULT;
        }
        return new com.codingagent.agent.ProjectAgentConfig(
                p.getAgentInstructions(),
                com.codingagent.agent.ProjectAgentConfig.parseList(p.getEnabledTools()),
                p.getApprovalPolicy(),
                p.getMaxTurns(),
                p.getTokenBudget(),
                com.codingagent.agent.ProjectAgentConfig.parseList(p.getExcludePaths()),
                p.getModelOverride());
    }

    /** 删除项目及其下会话。会话的工作区同步回收。 */
    public void deleteProject(Long projectId, Long userId) {
        Project p = getProject(projectId, userId);
        List<Session> sessions = sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Session>lambdaQuery()
                        .eq(Session::getProjectId, projectId));
        for (Session s : sessions) {
            purgeWorkspace(s.getId(), true);
        }
        // message / turn_checkpoint / session_rewind / session / mcp / skill 都有外键指向上游，
        // 必须自底向上删（子表已在 purgeWorkspace(deleteRecords=true) 里清掉），
        // 否则 session 删不动，整体抛外键违约变成「服务内部错误」
        sessionMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Session>lambdaQuery()
                        .eq(Session::getProjectId, projectId));
        mcpServerMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<McpServer>lambdaQuery()
                        .eq(McpServer::getProjectId, projectId));
        skillMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Skill>lambdaQuery()
                        .eq(Skill::getProjectId, projectId));
        projectMapper.deleteById(p.getId());
    }

    // ---------- 会话 ----------

    public Session createSession(Long userId, Long projectId, String title, String policy) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }

        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path ws = Path.of(workspaceProperties.getWorkspaceRoot(), sessionId).toAbsolutePath().normalize();

        // 审批策略三级：会话指定 > 项目配置 > 全局默认
        String resolvedPolicy = (policy != null && !policy.isBlank()) ? policy.trim()
                : (project.getApprovalPolicy() != null && !project.getApprovalPolicy().isBlank()
                        ? project.getApprovalPolicy().trim()
                        : "ASK_ON_WRITE");

        Session s = new Session();
        s.setId(sessionId);
        s.setProjectId(projectId);
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? "会话 " + sessionId.substring(5, 13) : title);
        s.setWorkspacePath(ws.toString());
        s.setBranchName("coding-agent/" + sessionId.substring(5, 13));
        s.setStatus("RUNNING");
        s.setApprovalPolicy(resolvedPolicy);
        s.setWorkspacePurged(false);
        s.setPromptTokens(0L);
        s.setCompletionTokens(0L);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());

        // 顺序要紧：先初始化工作区，成功了才写库。
        // 反过来的话，clone 失败会留下一条没有代码、永远跑不了的孤儿会话。
        // initialize 是幂等的，activate() 里再调一次只会跳过。
        // 初始化失败会抛 InitException，由全局处理器映射成 400 并带上具体原因。
        workspaceInitializer.initialize(project, ws, s.getBranchName());

        sessionMapper.insert(s);

        audit(sessionId, userId, "SESSION_LIFECYCLE", null,
                "创建会话 policy=" + resolvedPolicy, null);
        return s;
    }

    /**
     * 激活会话：建工作区、拉代码、起沙箱。
     *
     * <p>工作区已回收时拒绝激活——这是设计文档里定的语义：回收后只能回看历史。
     */
    public SessionRuntime activate(Session session) {
        SessionRuntime existing = runtimes.get(session.getId());
        if (existing != null) {
            return existing;
        }
        if (Boolean.TRUE.equals(session.getWorkspacePurged())) {
            throw new IllegalStateException("工作区已回收，只能回看历史。请新建会话以继续。");
        }

        Project project = projectMapper.selectById(session.getProjectId());
        Path ws = Path.of(session.getWorkspacePath());

        // 把代码弄进工作区：clone / 复制 + 建会话分支。
        // 这一步不做的话 agent 是在一个空目录里干活。
        workspaceInitializer.initialize(project, ws, session.getBranchName());

        SandboxSession sandbox = sandboxDriver.create(new SandboxDriver.CreateRequest(
                session.getId(), ws.toString(), imageFor(session), sandboxProperties.getNetwork(),
                sandboxProperties.getCpu(), sandboxProperties.getMemory()));

        AgentLoop.SessionPolicy policy = parsePolicy(session.getApprovalPolicy());
        SessionRuntime runtime = new SessionRuntime(session.getId(), sandbox, policy,
                gateOf(session.getId()));
        runtimes.put(session.getId(), runtime);
        log.info("[{}] 会话已激活，工作区 {} 策略 {}", session.getId(), ws, policy);
        return runtime;
    }

    private String imageFor(Session session) {
        Project p = projectMapper.selectById(session.getProjectId());
        if (p != null && p.getSandboxImage() != null && !p.getSandboxImage().isBlank()) {
            return p.getSandboxImage();
        }
        return sandboxProperties.getDefaultImage();
    }

    private static AgentLoop.SessionPolicy parsePolicy(String raw) {
        if (raw == null) {
            return AgentLoop.SessionPolicy.ASK_ON_WRITE;
        }
        return switch (raw.trim().toUpperCase()) {
            case "AUTO" -> AgentLoop.SessionPolicy.AUTO;
            case "ALWAYS_ASK" -> AgentLoop.SessionPolicy.ALWAYS_ASK;
            default -> AgentLoop.SessionPolicy.ASK_ON_WRITE;
        };
    }

    // ---------- 执行 ----------

    /**
     * 跑一轮对话，把事件流吐给 SSE。
     *
     * <p>并发闸门在这里收：超出 {@code maxConcurrentSessions} 时等待，
     * 而不是直接拒绝——排队比让用户反复点重试友好。
     */
    public Flux<AgentEvent> sendMessage(Session session, String content, Long userId) {
        // 先抢闸门、再 activate：闸门在 runtime 存在之前就必须生效，
        // 否则回滚会在「还没建出 runtime」的窗口里拿到空锁放行。
        // activate 可能抛（工作区已回收），那也必须把闸门还回去。
        AtomicBoolean gate = gateOf(session.getId());
        if (!gate.compareAndSet(false, true)) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请等当前轮次结束"));
        }
        // 整个同步段都要包住：activate 之后还有 loadHistory / ensureInitialCheckpoint /
        // persistUserMessage / 多次 DB 查询 / MCP 建连，任何一处抛异常都不能让闸门泄漏。
        // 泄漏的后果很重——这个会话的回滚与发消息会被永久卡死（闸门再没人归还）。
        try {
            SessionRuntime runtime = activate(session);

            // 顺序要紧：先读历史（此时本轮输入还没进库），再落盘用户输入，
            // 否则 history 里会带上本轮输入，agent loop 又追加一次，模型看到两条一模一样的话
            List<ChatMessage> history = loadHistory(session.getId());

            // 轮次编号是【会话全局】序号，单调递增、永不复用：对全会话（含已回滚行）取 max，
            // 回滚后继续对话的新轮号才不会与旧时间线撞。run 中途失败时 USER 行的轮号已消耗，
            // 下次 run 从 max+1 接续——不复用。
            int baseTurn = maxTurnOf(session.getId());

            // 回滚点 0 = 会话初始状态（首条消息发送前建立）。幂等：已有则跳过，
            // 升级前建的旧会话在第一次发消息时补建。
            ensureInitialCheckpoint(session);

            // 用户输入算它触发的 run 的第一轮：回滚到该轮保留此输入，回滚到再往前一轮连输入一起撤销
            persistUserMessage(session, content, baseTurn + 1);

            // run 期间跟踪当前全局轮：TurnStarted(k) 时置 baseTurn + k。
            // AgentEvent.TurnStarted 的 turn 是 per-run 的（每次 sendMessage 从 1 重数），
            // 两个 run 的 turn=1 会撞，不能直接落库。
            AtomicInteger currentTurn = new AtomicInteger(baseTurn);

            // 项目级 agent 配置：指令、工具白名单、预算覆盖
            Project project = projectMapper.selectById(session.getProjectId());
            com.codingagent.agent.ProjectAgentConfig agentConfig = agentConfigOf(project);

            // 项目挂载的 MCP 服务器与技能
            List<McpServer> mcpServers = mcpServerMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<McpServer>lambdaQuery()
                            .eq(McpServer::getProjectId, session.getProjectId()));
            List<Skill> skills = skillMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<Skill>lambdaQuery()
                            .eq(Skill::getProjectId, session.getProjectId())
                            .eq(Skill::getEnabled, true));
            com.codingagent.skill.SkillRegistry skillRegistry = new com.codingagent.skill.SkillRegistry(skills);

            McpServerManager.Session mcpSession = mcpServerManager.connectAll(mcpServers);
            List<com.codingagent.mcp.McpToolAdapter> mcpTools = mcpServerManager.toolsOf(mcpSession);
            if (!mcpTools.isEmpty()) {
                log.info("[{}] 挂载 {} 个 MCP 工具", session.getId(), mcpTools.size());
            }

            AgentLimits limits = AgentPropertiesMapper.toLimits(agentProperties);

            AgentLoop.SandboxContext ctx = new AgentLoop.SandboxContext(runtime.sandbox(), runtime.policy());

            ApprovalHandler handler = (approvalId, toolCall, preview) -> {
                audit(session.getId(), userId, "APPROVAL", toolCall.name(), preview, null);
                if (runtime.policy() == AgentLoop.SessionPolicy.AUTO) {
                    audit(session.getId(), userId, "APPROVAL", toolCall.name(), preview, true);
                    return true;
                }
                var future = runtime.registerApproval(approvalId);
                try {
                    Boolean approved = future.get(); // 阻塞等用户点击
                    audit(session.getId(), userId, "APPROVAL", toolCall.name(), preview, approved);
                    return approved;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    audit(session.getId(), userId, "APPROVAL", toolCall.name(), preview + " [被中断]", false);
                    return false;
                } catch (Exception e) {
                    audit(session.getId(), userId, "APPROVAL", toolCall.name(), preview + " [异常: " + e + "]", false);
                    return false;
                }
            };

            return agentLoop.run(session.getId(), history, content, ctx, limits, handler, null,
                            agentConfig, mcpTools, skillRegistry)
                    .doOnNext(ev -> persistEvent(session, userId, ev, baseTurn, currentTurn))
                    .doFinally(signal -> {
                        // MCP 连接随会话结束关闭，不复用到下一轮
                        mcpSession.close();
                        gate.set(false);
                        runtime.failAllApprovals();
                    });
        } catch (RuntimeException e) {
            // 同步段没走到 return，闸门不会被 doFinally 归还，必须在这里还
            gate.set(false);
            throw e;
        }
    }

    /** 用户裁决一个待处理的审批 */
    public boolean resolveApproval(Session session, String approvalId, boolean approved, Long userId) {
        SessionRuntime runtime = runtimes.get(session.getId());
        if (runtime == null) {
            return false;
        }
        boolean ok = runtime.resolveApproval(approvalId, approved);
        audit(session.getId(), userId, "APPROVAL_DECISION", null,
                "approvalId=" + approvalId + " approved=" + approved, approved);
        return ok;
    }

    // ---------- 持久化 ----------

    /**
     * 重建发给模型的历史。
     *
     * <p><b>必须过滤 {@code rolled_back=0}</b>：已回滚的行不进模型历史。
     * 注意与 UI 路径（{@link #listMessages}）分叉——界面要看全部行并自行分段渲染，
     * 同一张表两条筛选，别互相复用。翻转以整轮为单位，assistant(tool_calls)→tool
     * 的配对不可能被拆散（OpenAI 兼容层拒收的风险被结构性排除）。
     */
    private List<ChatMessage> loadHistory(String sessionId) {
        List<com.codingagent.storage.entity.Message> rows =
                messageMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<com.codingagent.storage.entity.Message>lambdaQuery()
                                .eq(com.codingagent.storage.entity.Message::getSessionId, sessionId)
                                .eq(com.codingagent.storage.entity.Message::getRolledBack, false)
                                .orderByAsc(com.codingagent.storage.entity.Message::getId));

        List<ChatMessage> result = new ArrayList<>();
        // system 提示不落库，激活时按当前沙箱环境重建；这里只回放 user/assistant/tool
        for (com.codingagent.storage.entity.Message row : rows) {
            result.add(toChatMessage(row));
        }
        return result;
    }

    /**
     * 行 → 发给模型的消息。
     *
     * <p><b>{@code reasoning} 刻意不带上。</b>它是推理模型的思考过程，只存下来供人回看；
     * MiMo 等模型明确要求 messages 里不得含 reasoning_content，否则后续轮次异常。
     * {@link ChatMessage} 里也压根没这个字段，双重保险。
     */
    private ChatMessage toChatMessage(com.codingagent.storage.entity.Message row) {
        return switch (row.getRole()) {
            case "USER" -> ChatMessage.user(row.getContent());
            case "ASSISTANT" -> ChatMessage.assistant(row.getContent(), parseToolCalls(row.getToolCalls()));
            case "TOOL" -> ChatMessage.toolResult(row.getToolCallId(), row.getToolName(), row.getContent());
            default -> ChatMessage.system(row.getContent());
        };
    }

    private List<ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return List.of(mapper.readValue(json, ToolCall[].class));
        } catch (Exception e) {
            log.warn("解析 tool_calls 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 事件落库。轮次（turn）也在这里定：TurnStarted(k) 到来时把全局轮号拨到
     * {@code baseTurn + k}，本轮的 TOOL / ASSISTANT 行落这个号。
     * 事件顺序保证 TurnStarted 先于本轮的工具/助手事件（sink 顺序即 doOnNext 顺序）。
     */
    private void persistEvent(Session session, Long userId, AgentEvent ev,
                              int baseTurn, AtomicInteger currentTurn) {
        try {
            if (ev instanceof AgentEvent.TurnStarted started) {
                currentTurn.set(baseTurn + started.turn());
            } else if (ev instanceof AgentEvent.ToolCallRequested req) {
                audit(session.getId(), userId, "TOOL_CALL", req.toolCall().name(),
                        req.toolCall().arguments(), null);
            } else if (ev instanceof AgentEvent.AssistantCommitted committed) {
                persistAssistantMessage(session, committed.content(), committed.toolCalls(),
                        committed.reasoning(), currentTurn.get());
                // 一轮结束（AssistantCommitted 是每轮必发的边界事件，TurnCompleted 只在
                // 整个 run 收敛的那一轮发）→ 给该轮打代码快照，成为可回滚点。
                // 只有落了检查点的轮才能被回滚：不能承诺还原到一个没快照的状态。
                persistTurnCheckpoint(session, currentTurn.get());
            } else if (ev instanceof AgentEvent.ToolCallCompleted done) {
                var row = new com.codingagent.storage.entity.Message();
                row.setSessionId(session.getId());
                row.setRole("TOOL");
                row.setContent(done.result().content());
                row.setToolCallId(done.toolCall().id());
                row.setToolName(done.toolCall().name());
                row.setTruncated(done.result().truncated());
                row.setTurn(currentTurn.get());
                row.setRolledBack(false);
                row.setPromptTokens(0);
                row.setCompletionTokens(0);
                row.setCreatedAt(Instant.now());
                messageMapper.insert(row);
            } else if (ev instanceof AgentEvent.UsageUpdated u) {
                session.setPromptTokens(u.promptTokens());
                session.setCompletionTokens(u.completionTokens());
                sessionMapper.updateById(session);
            }
        } catch (Exception e) {
            log.warn("持久化事件失败: {}", e.getMessage());
        }
    }

    /**
     * 给某轮打代码快照，插检查点行。失败只记日志——快照打不上就没有该轮的回滚点，
     * 但不能因此让消息落库失败。
     */
    private void persistTurnCheckpoint(Session session, int turn) {
        try {
            if (turnCheckpointMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<TurnCheckpoint>lambdaQuery()
                            .eq(TurnCheckpoint::getSessionId, session.getId())
                            .eq(TurnCheckpoint::getTurn, turn)) > 0) {
                return; // 幂等：同一轮不重复打点
            }
            GitSnapshotService.Snapshot snap = gitSnapshotService.snapshot(
                    Path.of(session.getWorkspacePath()), session.getId(),
                    GitSnapshotService.snapRef(session.getId()),
                    "coding-agent snapshot turn " + turn);
            TurnCheckpoint cp = new TurnCheckpoint();
            cp.setSessionId(session.getId());
            cp.setTurn(turn);
            cp.setHeadSha(snap.headSha());
            cp.setSnapshotSha(snap.snapshotSha());
            cp.setCreatedAt(Instant.now());
            turnCheckpointMapper.insert(cp);
        } catch (Exception e) {
            log.warn("[{}] 轮次 {} 检查点创建失败: {}", session.getId(), turn, e.getMessage());
        }
    }

    /** 回滚点 0 = 会话初始状态。幂等：已有则跳过。 */
    private void ensureInitialCheckpoint(Session session) {
        try {
            if (turnCheckpointMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<TurnCheckpoint>lambdaQuery()
                            .eq(TurnCheckpoint::getSessionId, session.getId())
                            .eq(TurnCheckpoint::getTurn, 0)) > 0) {
                return;
            }
            persistTurnCheckpoint(session, 0);
        } catch (Exception e) {
            log.warn("[{}] 初始检查点创建失败: {}", session.getId(), e.getMessage());
        }
    }

    /** 会话全局最大轮号（含已回滚行，保证轮号不复用）。旧数据 turn 为 NULL，视为 0。 */
    private int maxTurnOf(String sessionId) {
        List<Message> rows = messageMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Message>lambdaQuery()
                        .select(Message::getTurn)
                        .eq(Message::getSessionId, sessionId));
        int max = 0;
        for (Message row : rows) {
            if (row.getTurn() != null && row.getTurn() > max) {
                max = row.getTurn();
            }
        }
        return max;
    }

    /** 用户输入落库。只在 sendMessage 内部调用，保证与读历史的顺序可控。 */
    private void persistUserMessage(Session session, String content, int turn) {
        if (content == null || content.isBlank()) {
            return;
        }
        var row = new com.codingagent.storage.entity.Message();
        row.setSessionId(session.getId());
        row.setRole("USER");
        row.setContent(content);
        row.setTruncated(false);
        row.setTurn(turn);
        row.setRolledBack(false);
        row.setPromptTokens(0);
        row.setCompletionTokens(0);
        row.setCreatedAt(Instant.now());
        messageMapper.insert(row);
    }

    /**
     * assistant 消息落库。
     *
     * <p>tool_calls 一并存：恢复会话时必须还原成
     * 「assistant(含 tool_calls) → tool 结果」的成对序列，否则 OpenAI 兼容层会拒收。
     *
     * <p>{@code reasoning} 只写库供界面回看——{@link #loadHistory} 构造发给模型的
     * 消息时会刻意丢弃它。
     */
    private void persistAssistantMessage(Session session, String content,
                                         List<ToolCall> toolCalls, String reasoning, int turn) {
        var row = new com.codingagent.storage.entity.Message();
        row.setSessionId(session.getId());
        row.setRole("ASSISTANT");
        row.setContent(content == null ? "" : content);
        row.setReasoning(reasoning);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            try {
                row.setToolCalls(mapper.writeValueAsString(toolCalls));
            } catch (Exception e) {
                log.warn("tool_calls 序列化失败: {}", e.getMessage());
            }
        }
        row.setTruncated(false);
        row.setTurn(turn);
        row.setRolledBack(false);
        row.setPromptTokens(0);
        row.setCompletionTokens(0);
        row.setCreatedAt(Instant.now());
        messageMapper.insert(row);
    }

    private void audit(String sessionId, Long userId, String action,
                       String toolName, String detail, Boolean approved) {
        try {
            String truncated = detail != null && detail.length() > 8000
                    ? detail.substring(0, 8000) + "…[截断]" : detail;
            auditLogMapper.insertLog(sessionId, userId, action, toolName, truncated, approved);
        } catch (Exception e) {
            log.warn("审计写入失败: {}", e.getMessage());
        }
    }

    // ---------- 查询 ----------

    public Session getSession(String id) {
        return sessionMapper.selectById(id);
    }

    /** 回收工作区磁盘。保留期到期时由清理任务调用，用户也可手动触发。 */
    public boolean purgeWorkspace(String sessionId, boolean deleteRecords) {
        SessionRuntime runtime = runtimes.remove(sessionId);
        if (runtime != null) {
            runtime.close();
        }
        Session s = sessionMapper.selectById(sessionId);
        if (s == null) {
            return false;
        }
        try {
            Path ws = Path.of(s.getWorkspacePath());
            if (Files.exists(ws)) {
                try (var paths = Files.walk(ws)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // 单个文件删不掉不影响整体回收
                                }
                            });
                }
            }
        } catch (Exception e) {
            log.warn("[{}] 回收工作区失败: {}", sessionId, e.getMessage());
        }
        // deleteRecords=true 表示连历史一起清（项目删除用）；手动回收只清磁盘、留历史回看。
        // message / turn_checkpoint / session_rewind 都有外键指向 session，
        // 必须自底向上删：先 message，再检查点与操作日志，最后才能删 session——
        // 漏一个就是外键违约，整体变成「服务内部错误」。
        if (deleteRecords) {
            messageMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<Message>lambdaQuery()
                            .eq(Message::getSessionId, sessionId));
            turnCheckpointMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<TurnCheckpoint>lambdaQuery()
                            .eq(TurnCheckpoint::getSessionId, sessionId));
            sessionRewindMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.codingagent.storage.entity.SessionRewind>lambdaQuery()
                            .eq(com.codingagent.storage.entity.SessionRewind::getSessionId, sessionId));
        }
        s.setWorkspacePurged(true);
        s.setPurgedAt(Instant.now());
        s.setStatus("ARCHIVED");
        sessionMapper.updateById(s);
        audit(sessionId, s.getUserId(), "SESSION_LIFECYCLE", null, "工作区已回收", null);
        return true;
    }

    public List<Session> listSessions(Long userId) {
        return sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Session>lambdaQuery()
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getUpdatedAt));
    }

    public List<Session> listSessionsByProject(Long projectId, Long userId) {
        // 归属校验：先确认项目是该用户的
        getProject(projectId, userId);
        return sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Session>lambdaQuery()
                        .eq(Session::getProjectId, projectId)
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getUpdatedAt));
    }

    /** 会话改动的 diff */
    public com.codingagent.web.dto.Dtos.DiffResponse sessionDiff(String sessionId) {
        Session s = requireSession(sessionId);
        return gitDiffService.diff(s.getWorkspacePath(), s.getBranchName());
    }

    /** 会话改动的 patch 文本，供下载 */
    public String sessionPatch(String sessionId) {
        Session s = requireSession(sessionId);
        return gitDiffService.patch(s.getWorkspacePath(), s.getBranchName());
    }

    private Session requireSession(String sessionId) {
        Session s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return s;
    }

    public List<com.codingagent.storage.entity.Message> listMessages(String sessionId) {
        return messageMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.codingagent.storage.entity.Message>lambdaQuery()
                        .eq(com.codingagent.storage.entity.Message::getSessionId, sessionId)
                        .orderByAsc(com.codingagent.storage.entity.Message::getId));
    }

    public boolean isActive(String sessionId) {
        return runtimes.containsKey(sessionId);
    }

    /**
     * 会话此刻是否正有 agent 在跑（run 期间闸门被占）。
     *
     * <p>读路径不建闸门条目——避免每次前端轮询都往 map 里塞东西。
     */
    public boolean isRunning(String sessionId) {
        AtomicBoolean gate = runGates.get(sessionId);
        return gate != null && gate.get();
    }

    /**
     * 抢会话执行闸门——与 {@link #sendMessage} 共用同一把锁，
     * 回滚/恢复与 agent 运行借此互斥。
     *
     * <p>危险操作（回滚）对进行中的 run 的处置是<b>拒绝</b>而不是中断：
     * 中断会在工具执行半途留下不一致的工作区，而「等当前轮次结束」只多等几十秒，
     * 换来可预期的一致性。抢不到闸门 → false，由调用方回 409。
     *
     * <p>闸门与「会话是否已激活」无关——未激活时同样占锁，
     * 因为下一毫秒 sendMessage 就会建出 runtime 开跑。
     */
    public boolean tryAcquireRunGate(String sessionId) {
        return gateOf(sessionId).compareAndSet(false, true);
    }

    /** 与 {@link #tryAcquireRunGate} 配对释放。完成/失败都要复位。 */
    public void releaseRunGate(String sessionId) {
        gateOf(sessionId).set(false);
    }
}
