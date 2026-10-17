package com.example.contentagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供一个共享 ChatClient。各 Agent 通过 system prompt 区分角色，
 * 而不是各自 new 一个模型实例——保持配置单一来源。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
