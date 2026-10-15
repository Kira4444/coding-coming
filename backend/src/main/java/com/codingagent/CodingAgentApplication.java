package com.codingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Coding Agent 服务入口。
 *
 * <p>模块边界（单体部署，预留拆分点）：
 * <ul>
 *   <li>{@code web}          — HTTP API、SSE、鉴权</li>
 *   <li>{@code agent}        — agent loop、工具集、上下文管理</li>
 *   <li>{@code model}        — LLM provider 抽象（OpenAI 兼容）</li>
 *   <li>{@code sandbox}      — 容器生命周期、命令执行</li>
 *   <li>{@code storage}      — 持久化</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class CodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }
}
