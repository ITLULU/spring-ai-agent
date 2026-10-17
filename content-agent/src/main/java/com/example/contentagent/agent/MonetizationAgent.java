package com.example.contentagent.agent;

import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.MonetizationPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Agent 2：变现规划。基于分析结果生成可执行的变现方案。
 * 温度建议低一些（规划要稳定），但沿用全局配置即可满足 MVP。
 */
@Component
public class MonetizationAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public MonetizationAgent(ChatClient chatClient, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    public MonetizationPlan plan(ContentAnalysis analysis) {
        String system = promptLoader.load("monetization-system.txt");
        String user = """
                核心主题：%s
                
                技术要点：
                %s
                
                差异化角度：
                %s
                
                目标读者：
                %s
                
                读者痛点/钩子：
                %s
                
                变现钩子：%s
                """.formatted(
                analysis.coreTopic(),
                bullets(analysis.technicalPoints()),
                bullets(analysis.uniqueAngles()),
                bullets(analysis.targetAudience()),
                bullets(analysis.painPoints()),
                analysis.monetizationHook());

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(MonetizationPlan.class);
    }

    private String bullets(java.util.List<String> items) {
        if (items == null || items.isEmpty()) {
            return "（无）";
        }
        return items.stream().map(s -> "- " + s).collect(Collectors.joining("\n"));
    }
}
