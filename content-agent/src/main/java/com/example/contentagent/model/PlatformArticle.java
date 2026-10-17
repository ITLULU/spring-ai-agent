package com.example.contentagent.model;

import java.util.List;

/**
 * 平台稿件：由 ZhihuWriterAgent / WechatWriterAgent 产出。
 *
 * @param platform    目标平台标识：zhihu | wechat
 * @param title       平台风格标题
 * @param alternativeTitles 3 个备选标题（供人工挑选）
 * @param bodyMarkdown 正文 markdown（平台风格已适配）
 * @param tags        话题/标签
 * @param cta         文末引流语
 * @param readingMinutes 预计阅读时长（分钟）
 */
public record PlatformArticle(
        String platform,
        String title,
        List<String> alternativeTitles,
        String bodyMarkdown,
        List<String> tags,
        String cta,
        int readingMinutes
) {
}
