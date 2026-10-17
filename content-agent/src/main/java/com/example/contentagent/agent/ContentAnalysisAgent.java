package com.example.contentagent.agent;

import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.ContentInput;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Agent 1：内容分析。把原始 markdown 提炼成结构化可变现要素。
 * 使用 Spring AI 结构化输出（.entity()）——模型被约束按 ContentAnalysis 的 JSON schema 返回。
 */
@Component
public class ContentAnalysisAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ContentAnalysisAgent(ChatClient chatClient, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    public ContentAnalysis analyze(ContentInput input) {
        String system = promptLoader.load("analysis-system.txt");
        String user = """
                标题：%s
                
                原始 markdown 正文：
                ---
                %s
                ---
                """.formatted(input.title(), input.rawMarkdown());

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(ContentAnalysis.class);
    }
}
