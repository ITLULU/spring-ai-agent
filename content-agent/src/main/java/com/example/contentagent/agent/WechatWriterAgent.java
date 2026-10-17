package com.example.contentagent.agent;

import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.ContentInput;
import com.example.contentagent.model.PlatformArticle;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 4：公众号稿件生成。公众号风格 = 手机屏短段落 + 个人叙事 + 明确 CTA 引流。
 */
@Component
public class WechatWriterAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public WechatWriterAgent(ChatClient chatClient, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    public PlatformArticle write(ContentInput input, ContentAnalysis analysis) {
        String system = promptLoader.load("wechat-system.txt");
        String user = PromptSupport.buildWriterUser(input, analysis);

        PlatformArticle article = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(PlatformArticle.class);

        if (article == null || article.platform() == null || article.platform().isBlank()) {
            return new PlatformArticle("wechat",
                    article != null ? article.title() : input.title(),
                    article != null ? article.alternativeTitles() : List.of(),
                    article != null ? article.bodyMarkdown() : "",
                    article != null ? article.tags() : List.of(),
                    article != null ? article.cta() : "",
                    article != null ? article.readingMinutes() : 0);
        }
        return article;
    }
}
