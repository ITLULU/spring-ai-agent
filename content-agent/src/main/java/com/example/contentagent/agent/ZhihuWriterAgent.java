package com.example.contentagent.agent;

import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.ContentInput;
import com.example.contentagent.model.PlatformArticle;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Agent 3：知乎稿件生成。知乎风格 = 搜索长尾 + 技术深度 + 个人观点钩子。
 */
@Component
public class ZhihuWriterAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ZhihuWriterAgent(ChatClient chatClient, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    public PlatformArticle write(ContentInput input, ContentAnalysis analysis) {
        String system = promptLoader.load("zhihu-system.txt");
        String user = PromptSupport.buildWriterUser(input, analysis);

        PlatformArticle article = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(PlatformArticle.class);

        // 兜底：模型可能忘记回填 platform 字段
        if (article == null || article.platform() == null || article.platform().isBlank()) {
            return new PlatformArticle("zhihu",
                    article != null ? article.title() : input.title(),
                    article != null ? article.alternativeTitles() : java.util.List.of(),
                    article != null ? article.bodyMarkdown() : "",
                    article != null ? article.tags() : java.util.List.of(),
                    article != null ? article.cta() : "",
                    article != null ? article.readingMinutes() : 0);
        }
        return article;
    }
}
