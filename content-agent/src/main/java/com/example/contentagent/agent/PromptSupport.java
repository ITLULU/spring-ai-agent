package com.example.contentagent.agent;

import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.ContentInput;

import java.util.stream.Collectors;

/**
 * 写作类 Agent 共享的 user prompt 组装。
 */
final class PromptSupport {

    private PromptSupport() {
    }

    static String buildWriterUser(ContentInput input, ContentAnalysis analysis) {
        String seoKeywords = analysis.seoKeywords() == null ? "" :
                analysis.seoKeywords().stream().collect(Collectors.joining("、"));
        return """
                == 原始素材（你的想法/实战记录，唯一事实来源，不得编造其中的数据或结论）==
                标题：%s
                
                正文：
                ---
                %s
                ---
                
                == 分析结果（供你组织文章结构，不要直接罗列）==
                核心主题：%s
                技术要点：%s
                差异化角度：%s
                目标读者：%s
                读者痛点：%s
                SEO 关键词：%s
                """.formatted(
                input.title(),
                input.rawMarkdown(),
                analysis.coreTopic(),
                join(analysis.technicalPoints()),
                join(analysis.uniqueAngles()),
                join(analysis.targetAudience()),
                join(analysis.painPoints()),
                seoKeywords);
    }

    private static String join(java.util.List<String> items) {
        return items == null || items.isEmpty() ? "（无）"
                : items.stream().collect(Collectors.joining("；"));
    }
}
