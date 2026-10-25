package com.example.kbagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * kb-agent 的框架装配：ChatClient + 会话窗口。
 *
 * @Lazy ChatModel 不是可选优化——工单 Agent 的工具里含 ChatClient 自身要用的
 * toolCallbacks，而 starter 会把所有 ToolCallbackProvider 喂给 ChatModel 的工具解析链，
 * 不 @Lazy 就回到 rag-kb 踩过的那个环（openAiChatModel → toolCallingManager →
 * toolCallbackResolver → provider → … → ChatModel）。
 */
@Configuration
@EnableScheduling
public class AgentChatConfig {

    /**
     * 窗口只存问/答两条一轮（与 rag-kb 同一裁剪口径）：
     * 工单会话里"上一轮看到的政策原文"不该成为下一轮的依据，
     * 依据必须重新走一次工具调用，这样审核轨迹才和实际调用一致。
     */
    @Bean
    public ChatMemory chatMemory(@Value("${ticket.agent.memory-max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    public ChatClient ticketChatClient(@Lazy ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
