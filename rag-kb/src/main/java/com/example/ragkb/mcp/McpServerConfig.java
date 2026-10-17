package com.example.ragkb.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 装配：把 RagKbTools 上标注的 @Tool 方法注册为 MCP 协议工具。
 * starter（spring-ai-starter-mcp-server-webmvc）检测到 ToolCallbackProvider Bean
 * 即自动完成 JSON-RPC/SSE 传输层——这里只是"声明哪些能力对外暴露"，零协议代码。
 * 同一批 Service 同时仍以 REST 端点形态服务（KbController/EvalController），
 * 两种传输共享同一实现，互不重复。
 */
@Configuration
public class McpServerConfig {

    /**
     * 注：openAiChatModel → toolCallingManager → toolCallbackResolver 会 eagerly 消费所有
     * ToolCallbackProvider，形成 Provider → RagKbTools → QaService/EvalJudge → openAiChatModel
     * 的环；已在 QaService/EvalJudge 的 ChatModel 注入点用 @Lazy 断开（两者构造时只存引用
     * 不调方法，首次 call 时才解析真实 bean）。
     */
    @Bean
    public ToolCallbackProvider ragKbToolCallbackProvider(RagKbTools ragKbTools) {
        return MethodToolCallbackProvider.builder().toolObjects(ragKbTools).build();
    }
}
