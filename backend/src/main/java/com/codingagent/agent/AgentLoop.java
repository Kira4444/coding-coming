package com.codingagent.agent;

import com.codingagent.model.ChatMessage;
import com.codingagent.model.ChatRequest;
import com.codingagent.model.ChatStreamEvent;
import com.codingagent.model.ModelRouter;
import com.codingagent.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 主循环。
 *
 * <p>结构照搬 Claude Agent SDK 的形态：消息流 → 模型 → 工具调用 → 结果回传 → 再进模型，
 * 直到模型不再要求工具调用，或触及轮次/预算上限。
 *
 * <p>阻塞式实现（跑在 {@code boundedElastic} 上）是刻意的：工具执行是同步的，
 * 审批要等用户点击，硬套响应式会让控制流散进回调里没法读。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ModelRouter router;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public AgentLoop(ModelRouter router, ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.router = router;
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    /**
     * 跑一轮对话。返回的 {@link Flux} 会把过程事件推给调用方（SSE）。
     *
     * @param history  会话已有的消息（不含本轮用户输入）
     * @param userTurn 本轮用户输入
     */
    public Flux<AgentEvent> run(
            String sessionId,
            List<ChatMessage> history,
            String userTurn,
            SandboxContext sandboxCtx,
            AgentLimits limits,
            ApprovalHandler approval,
            String taskType) {
        return run(sessionId, history, userTurn, sandboxCtx, limits, approval, taskType,
                ProjectAgentConfig.DEFAULT);
    }

    /**
     * @param projectConfig 项目级 agent 配置：指令、工具白名单、执行预算覆盖。
     *                      为 {@link ProjectAgentConfig#DEFAULT} 时全部走全局默认。
     */
    public Flux<AgentEvent> run(
            String sessionId,
            List<ChatMessage> history,
            String userTurn,
            SandboxContext sandboxCtx,
            AgentLimits limits,
            ApprovalHandler approval,
            String taskType,
            ProjectAgentConfig projectConfig) {
        return run(sessionId, history, userTurn, sandboxCtx, limits, approval, taskType,
                projectConfig, List.of(), com.codingagent.skill.SkillRegistry.empty());
    }

    /**
     * @param extraTools 会话级动态工具（MCP），与内置工具合并
     * @param skills     本次执行可用的技能，索引进系统提示，正文靠 load_skill 拉
     */
    public Flux<AgentEvent> run(
            String sessionId,
            List<ChatMessage> history,
            String userTurn,
            SandboxContext sandboxCtx,
            AgentLimits limits,
            ApprovalHandler approval,
            String taskType,
            ProjectAgentConfig projectConfig,
            List<? extends Tool> extraTools,
            com.codingagent.skill.SkillRegistry skills) {

        // 项目配置可以收紧预算（比如某个仓库特大，想压低轮次）
        AgentLimits effective = projectConfig.applyTo(limits);
        ToolSet tools = new ToolSet(toolRegistry, extraTools);
        com.codingagent.skill.SkillRegistry safeSkills =
                skills == null ? com.codingagent.skill.SkillRegistry.empty() : skills;

        List<ChatMessage> messages = new ArrayList<>();
        // system 提示不落库：按当前沙箱环境实时重建，路径/平台变了也不会用到过期版本
        messages.add(ChatMessage.system(
                SystemPrompt.build(sandboxCtx.sandbox(), projectConfig.instructions(), safeSkills.indexText())));
        messages.addAll(history);
        messages.add(ChatMessage.user(userTurn));

        ContextManager ctxMgr = new ContextManager(effective);
        AtomicInteger turnCount = new AtomicInteger(0);
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();

        return Flux.<AgentEvent>create(sink -> {
            try {
                loop(sessionId, messages, sandboxCtx, effective, approval, taskType,
                        projectConfig, tools, safeSkills,
                        ctxMgr, turnCount, promptTokens, completionTokens, sink);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.next(new AgentEvent.Failed("会话被中断"));
            } catch (Exception e) {
                log.error("[{}] agent 循环异常", sessionId, e);
                sink.next(new AgentEvent.Failed("执行异常: " + e.getMessage()));
            } finally {
                sink.complete();
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private void loop(
            String sessionId,
            List<ChatMessage> messages,
            SandboxContext sandboxCtx,
            AgentLimits limits,
            ApprovalHandler approval,
            String taskType,
            ProjectAgentConfig projectConfig,
            ToolSet tools,
            com.codingagent.skill.SkillRegistry skills,
            ContextManager ctxMgr,
            AtomicInteger turnCount,
            AtomicLong promptTokens,
            AtomicLong completionTokens,
            reactor.core.publisher.FluxSink<AgentEvent> sink) throws InterruptedException {

        while (true) {
            if (turnCount.incrementAndGet() > limits.maxTurns()) {
                sink.next(new AgentEvent.Failed(
                        "已达单会话最大轮次 " + limits.maxTurns() + "，请新开会话继续"));
                return;
            }
            if (!ctxMgr.hasBudget(messages)) {
                sink.next(new AgentEvent.Failed(
                        "token 预算已耗尽（" + ctxMgr.estimate(messages) + "），请新开会话继续"));
                return;
            }

            List<ChatMessage> sent = ctxMgr.protect(messages);
            ModelRouter.ResolvedModel resolved = router.resolve(taskType, projectConfig.modelOverride());

            // 项目白名单过滤：没开的工具既不出现在模型可见的 tools 里，
            // 也不会被分发执行——两头都堵，避免模型幻觉调用后才发现被禁
            List<ChatRequest.ToolSpec> specs = tools.specs().stream()
                    .filter(s -> projectConfig.isToolEnabled(s.name()))
                    .map(s -> new ChatRequest.ToolSpec(s.name(), s.description(), s.parameters()))
                    .toList();

            ChatRequest request = ChatRequest.builder()
                    .model(resolved.model())
                    .messages(sent)
                    .tools(specs)
                    .temperature(0.2)
                    .build();

            sink.next(new AgentEvent.TurnStarted(sessionId, turnCount.get()));

            // —— 流式收集本轮模型输出 ——
            StringBuilder text = new StringBuilder();
            // 推理过程：单独累积，只供界面回看，绝不并入 text
            StringBuilder reasoning = new StringBuilder();
            Map<Integer, ToolCallAccumulator> callAcc = new LinkedHashMap<>();
            AtomicBoolean sawError = new AtomicBoolean(false);
            AtomicLong turnPrompt = new AtomicLong();
            AtomicLong turnCompletion = new AtomicLong();

            Flux<ChatStreamEvent> stream = resolved.provider().chatStream(request);
            stream.toStream().forEach(ev -> {
                if (ev instanceof ChatStreamEvent.TextDelta d) {
                    text.append(d.text());
                    sink.next(new AgentEvent.TextDelta(d.text()));
                } else if (ev instanceof ChatStreamEvent.ReasoningDelta d) {
                    // 累积下来给界面回看，但绝不能 append 到 text——
                    // 那会被写进 assistant 消息，再回传给模型时违反 MiMo 的约束。
                    reasoning.append(d.text());
                    sink.next(new AgentEvent.ReasoningDelta(d.text()));
                } else if (ev instanceof ChatStreamEvent.ToolCallDelta d) {
                    callAcc.computeIfAbsent(d.index(), i -> new ToolCallAccumulator())
                            .accept(d);
                } else if (ev instanceof ChatStreamEvent.Usage u) {
                    turnPrompt.set(u.promptTokens());
                    turnCompletion.set(u.completionTokens());
                } else if (ev instanceof ChatStreamEvent.Error e) {
                    sawError.set(true);
                    sink.next(new AgentEvent.Failed(e.message()));
                }
            });

            if (sawError.get()) {
                return;
            }

            List<ToolCall> toolCalls = callAcc.entrySet().stream()
                    .sorted(Comparator.comparingInt(Map.Entry::getKey))
                    .map(e -> e.getValue().toToolCall())
                    .toList();

            // —— 没有工具调用：本轮收敛，收工 ——
            if (toolCalls.isEmpty()) {
                promptTokens.addAndGet(turnPrompt.get());
                completionTokens.addAndGet(turnCompletion.get());
                sink.next(new AgentEvent.UsageUpdated(
                        promptTokens.get(), completionTokens.get(),
                        promptTokens.get() + completionTokens.get()));
                messages.add(ChatMessage.assistant(text.toString(), null));
                sink.next(new AgentEvent.AssistantCommitted(
                        text.toString(), null, emptyToNull(reasoning.toString())));
                sink.next(new AgentEvent.TurnCompleted(turnCount.get()));
                return;
            }

            // —— 有工具调用：逐个审批并执行 ——
            List<ToolCall> executed = new ArrayList<>();
            List<ChatMessage> results = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                sink.next(new AgentEvent.ToolCallRequested(call));
                executed.add(call);

                String preview = buildPreview(call);
                boolean needApproval = requiresApproval(approval, call, sandboxCtx, tools);
                if (needApproval) {
                    String approvalId = UUID.randomUUID().toString();
                    sink.next(new AgentEvent.ApprovalRequired(approvalId, call, approvalReason(call), preview));
                    boolean approved = approval.approve(approvalId, call, preview);
                    sink.next(new AgentEvent.ApprovalResolved(approvalId, approved));
                    if (!approved) {
                        results.add(ChatMessage.toolResult(call.id(), call.name(),
                                "用户拒绝了这次操作。请说明你原本打算做什么，让用户决定下一步。"));
                        continue;
                    }
                }

                long start = System.currentTimeMillis();
                ToolResult result = execute(call, sandboxCtx, limits, projectConfig, tools, skills);
                long elapsed = System.currentTimeMillis() - start;
                sink.next(new AgentEvent.ToolCallCompleted(call, result, elapsed));

                String content = result.ok()
                        ? result.content()
                        : "[工具执行失败] " + result.content();
                results.add(ChatMessage.toolResult(call.id(), call.name(), content));
            }

            promptTokens.addAndGet(turnPrompt.get());
            completionTokens.addAndGet(turnCompletion.get());

            // 消息顺序必须是：assistant(含 tool_calls) → tool 结果…，否则 OpenAI 兼容层会报错
            messages.add(ChatMessage.assistant(text.toString(), executed));
            sink.next(new AgentEvent.AssistantCommitted(
                    text.toString(), executed, emptyToNull(reasoning.toString())));
            messages.addAll(results);
        }
    }

    private boolean requiresApproval(ApprovalHandler approval, ToolCall call, SandboxContext ctx, ToolSet tools) {
        if (ctx.policy() == SessionPolicy.AUTO) {
            return false;
        }
        if (ctx.policy() == SessionPolicy.ALWAYS_ASK) {
            return true;
        }
        // ASK_ON_WRITE：交给工具自己判定
        return tools.find(call.name())
                .map(t -> {
                    try {
                        return t.requiresApproval(mapper.readTree(
                                call.arguments() == null ? "{}" : call.arguments()));
                    } catch (Exception e) {
                        // 参数都解析不了，更要拦
                        return true;
                    }
                })
                .orElse(true); // 未知工具一律拦
    }

    private ToolResult execute(ToolCall call, SandboxContext ctx, AgentLimits limits,
                               ProjectAgentConfig projectConfig, ToolSet tools,
                               com.codingagent.skill.SkillRegistry skills) {
        var toolOpt = tools.find(call.name());
        if (toolOpt.isEmpty()) {
            return ToolResult.failure("未知工具: " + call.name()
                    + "。可用工具: " + tools.names());
        }
        if (!projectConfig.isToolEnabled(call.name())) {
            return ToolResult.failure("工具 " + call.name()
                    + " 在本项目中已禁用。可用工具: " + projectConfig.enabledToolsList());
        }
        Tool tool = toolOpt.get();
        JsonNode args;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            args = mapper.readTree(raw);
        } catch (Exception e) {
            return ToolResult.failure("工具参数不是合法 JSON: " + e.getMessage()
                    + "。收到的参数: " + call.arguments());
        }
        long start = System.currentTimeMillis();
        try {
            ToolResult r = tool.execute(new ToolContext(ctx.sandbox(), limits, projectConfig, skills), args);
            log.debug("[{}] 工具 {} 耗时 {}ms ok={}", ctx.sandbox().id(), call.name(),
                    System.currentTimeMillis() - start, r.ok());
            return r;
        } catch (Exception e) {
            log.warn("[{}] 工具 {} 执行异常", ctx.sandbox().id(), call.name(), e);
            return ToolResult.failure("工具执行异常: " + e.getMessage());
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String buildPreview(ToolCall call) {        String args = call.arguments() == null ? "" : call.arguments();
        if (args.length() > 2000) {
            args = args.substring(0, 2000) + "…";
        }
        return call.name() + " " + args;
    }

    private static String approvalReason(ToolCall call) {
        return switch (call.name()) {
            case "shell" -> "shell 命令会修改文件系统或系统状态";
            case "write_file" -> "按当前策略，写文件需要确认";
            default -> "按当前策略，该操作需要确认";
        };
    }

    /** 流式 tool call 的增量聚合器 */
    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder args = new StringBuilder();

        void accept(ChatStreamEvent.ToolCallDelta d) {
            if (d.id() != null && !d.id().isBlank()) {
                id = d.id();
            }
            if (d.name() != null && !d.name().isBlank()) {
                name = d.name();
            }
            if (d.argumentsDelta() != null) {
                args.append(d.argumentsDelta());
            }
        }

        ToolCall toToolCall() {
            String finalId = (id == null || id.isBlank())
                    ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    : id;
            return ToolCall.of(finalId, name, args.toString());
        }
    }

    /** 会话级审批策略 */
    public enum SessionPolicy {
        AUTO, ASK_ON_WRITE, ALWAYS_ASK
    }

    /** agent 执行期的环境上下文 */
    public record SandboxContext(
            com.codingagent.sandbox.SandboxSession sandbox,
            SessionPolicy policy
    ) {
    }
}
