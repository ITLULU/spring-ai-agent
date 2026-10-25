package com.example.ragkb.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Agent 抽象层装配（AGT-01/AGT-02）：把"裸调 ChatModel"升级为 Spring AI 的
 * ChatClient + ChatMemory 形态，为阶段 2 工单 Agent 复用同一套会话/编排能力。
 *
 * 为什么 ChatMemory 自己声明 Bean 而不是引 spring-ai-starter-model-chat-memory：
 * 自动配置只会给一个默认的 InMemory 实现，而这里要把窗口大小做成配置键
 * （rag.memory.max-messages）并让 QaService/评测链路能拿到同一个实例；
 * 少一个依赖也少一条"默认 Bean 和我的 Bean 谁生效"的排查路径。
 */
@Configuration
public class AgentChatConfig {

    /**
     * 会话记忆仓库：进程内（与本项目 memory 模式的存储纪律一致）。
     * 演进方向是落 Redis/PG——多实例共享会话时才需要，见排查清单 SCA/PER 批次。
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    /**
     * 滑动窗口记忆：按消息条数裁剪，不是按 token 裁剪。
     * 窗口里只放 question/answer（检索上下文走 system 模板，不进记忆），
     * 所以 20 条=10 轮问答，成本上界可算——这是"引用与记忆并存怎么裁剪"的答案。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository,
                                 @Value("${rag.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * 断环要断两处，光在 ChatModel 注入点写 @Lazy 不够（2026-10-25 实测：进程直接起不来，
     * 环是 qaService → kbChatClient → openAiChatModel → toolCallingManager →
     * toolCallbackResolver → ragKbToolCallbackProvider → ragKbTools → qaService）：
     * ① {@code ChatClient.builder(chatModel)} 在**构建期就会摸**代理（读默认 options），
     *    于是代理提前解析、环在此刻闭合；
     * ② 所以本 Bean 也要 @Lazy（容器不主动实例化），并且 QaService 的注入点同样 @Lazy
     *    ——拿到的是 ChatClient 代理，第一次真正 prompt() 时才建实例，那时上下文已全部就绪，
     *    openAiChatModel 的回指链不再构成创建期环。
     */
    @Bean
    @Lazy
    public ChatClient kbChatClient(@Lazy ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
