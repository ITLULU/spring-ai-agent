package com.example.kbagent.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Agent 的"模型节点"：分诊、答复装配、自由问答三种用法都在这里，
 * 工作流（TicketWorkflow）只负责编排与判定，不掺模型调用细节。
 *
 * 三处都走 ChatClient 抽象层（AGT-01 的口径延续到阶段2）：
 * ① 提示词外置成 .st 资源，改文案不动 Java；
 * ② 分诊与装配用 {@code .entity(Record)} 结构化输出——工单流水线要按字段路由，
 *    拿一段自由文本再正则抠字段是最脆的做法；自由问答用自由文本（人要读）。
 * ③ 自由问答挂 MCP 发现的工具 + 本地工单工具，模型自己决定调哪个（Function Calling 侧），
 *    而流水线里的检索是**代码节点直接调工具**（可复现侧）——两条路都留着，
 *    是因为它们答的是不同问题：前者证明 Agent 能用工具，后者保证线上行为可控。
 */
@Service
public class TicketBrain {

    /** 分诊结果：category/priority 用于路由，searchQuery 是"口语诉求→检索问句"的改写产物 */
    public record Classification(String category, String priority, String searchQuery) {
    }

    /**
     * 答复装配产物。selfConfidence 只占最终置信度的一小部分（权重见配置）：
     * 模型自评普遍偏高，把它当唯一判据等于让模型自己批自己的条。
     */
    public record Draft(String reply, boolean citedFaithfully, double selfConfidence) {
    }

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final KbMcpGateway gateway;
    private final TicketLocalTools localTools;
    private final Resource classifyPrompt;
    private final Resource draftPrompt;
    private final Resource agenticSystem;

    public TicketBrain(ChatClient ticketChatClient, ChatMemory chatMemory, KbMcpGateway gateway,
                       TicketLocalTools localTools,
                       @Value("classpath:prompts/classify.st") Resource classifyPrompt,
                       @Value("classpath:prompts/draft.st") Resource draftPrompt,
                       @Value("classpath:prompts/agentic-system.st") Resource agenticSystem) {
        this.chatClient = ticketChatClient;
        this.chatMemory = chatMemory;
        this.gateway = gateway;
        this.localTools = localTools;
        this.classifyPrompt = classifyPrompt;
        this.draftPrompt = draftPrompt;
        this.agenticSystem = agenticSystem;
    }

    public Classification classify(String title, String description) {
        return chatClient.prompt()
                .system(s -> s.text(classifyPrompt, StandardCharsets.UTF_8)
                        .param("title", safe(title)).param("description", safe(description)))
                .user("按要求的字段输出分诊结果。")
                .call().entity(Classification.class);
    }

    public Draft draft(String title, String description, String kbAnswerJson) {
        return chatClient.prompt()
                .system(s -> s.text(draftPrompt, StandardCharsets.UTF_8)
                        .param("title", safe(title)).param("description", safe(description))
                        .param("kbAnswer", safe(kbAnswerJson)))
                .user("按要求的字段输出工单回复。")
                .call().entity(Draft.class);
    }

    /**
     * Agentic 模式：模型自主选工具（MCP 发现的知识库工具 + 本地工单工具）并支持多轮。
     * conversationId 为空时不挂记忆 advisor——与 rag-kb QaService 同一纪律：
     * 无状态调用不能被历史污染，否则批量场景的结果不可复现。
     */
    public String ask(String question, String conversationId) {
        var request = chatClient.prompt()
                .system(s -> s.text(agenticSystem, StandardCharsets.UTF_8))
                .user(question)
                .toolCallbacks(gateway.discovered())
                .tools(localTools);
        String cid = conversationId == null || conversationId.isBlank() ? null : conversationId.trim();
        if (cid != null) {
            request = request.advisors(a -> a
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .param(ChatMemory.CONVERSATION_ID, cid));
        }
        return request.call().content();
    }

    /** 会话历史（阶段2"多轮会话可追溯"验收面） */
    public List<org.springframework.ai.chat.messages.Message> session(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? List.of() : chatMemory.get(conversationId.trim());
    }

    public void clearSession(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            chatMemory.clear(conversationId.trim());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
