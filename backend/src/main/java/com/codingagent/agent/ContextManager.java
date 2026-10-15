package com.codingagent.agent;

import com.codingagent.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算管理。
 *
 * <p>MVP 策略：<b>只做截断，不做自动压缩</b>（已知取舍，见 DESIGN.md）。
 * 工具输出在工具层已经截断过，这里再兜一层总量预算——
 * 单会话 token 预算耗尽时明确告知用户去开新会话，而不是让请求静默失败。
 */
public class ContextManager {

    private final AgentLimits limits;

    public ContextManager(AgentLimits limits) {
        this.limits = limits;
    }

    /** 估算整段对话的 token 占用 */
    public long estimate(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            total += m.estimatedTokens();
        }
        return total;
    }

    /**
     * 是否还有预算再跑一轮。留出的余量是为了保证最后一轮的输出能写进去。
     */
    public boolean hasBudget(List<ChatMessage> messages) {
        return estimate(messages) < limits.tokenBudget() * 0.85;
    }

    /**
     * 极端情况下对历史做保护性裁剪：保留 system 提示 + 最近若干条消息，
     * 中间丢弃的部分以一条摘要占位，避免模型完全失去上下文。
     *
     * <p>这是<b>兜底</b>而非「自动压缩」——真正的压缩是用便宜模型重写历史，
     * 属于二期能力。
     */
    public List<ChatMessage> protect(List<ChatMessage> messages) {
        if (estimate(messages) <= limits.tokenBudget()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        if (!messages.isEmpty() && messages.get(0).role() == ChatMessage.Role.SYSTEM) {
            result.add(messages.get(0));
        }
        int kept = 0;
        long budget = (long) (limits.tokenBudget() * 0.6);
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 1; i--) {
            ChatMessage m = messages.get(i);
            if (kept + m.estimatedTokens() > budget) {
                break;
            }
            tail.add(0, m);
            kept += m.estimatedTokens();
        }
        result.add(ChatMessage.system(
                "[上下文保护] 更早的 " + (messages.size() - tail.size() - 1)
                        + " 条消息已被裁剪以控制 token 用量。如需完整上下文请新开会话。"));
        result.addAll(tail);
        return result;
    }
}
