package com.codingagent.model;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型接入的唯一抽象。
 *
 * <p>DeepSeek / 智谱 GLM / vLLM 自部署都是 OpenAI 兼容协议，因此实现只有
 * {@link OpenAiCompatibleProvider} 一个；留接口是为将来接非兼容实现（或做 mock）。
 */
public interface LlmProvider {

    /**
     * 流式对话。实现方负责把上游的 SSE 帧翻译成 {@link ChatStreamEvent}，
     * 并保证流一定以 {@link ChatStreamEvent.Completed} 或 {@link ChatStreamEvent.Error} 收尾。
     */
    Flux<ChatStreamEvent> chatStream(ChatRequest request);

    /** 该 provider 的标识，用于日志与审计 */
    String id();
}
