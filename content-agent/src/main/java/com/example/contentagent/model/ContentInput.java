package com.example.contentagent.model;

import java.time.Instant;

/**
 * 一条待处理的内容输入：你的想法或项目实战 markdown。
 *
 * @param id          唯一标识（用文件名派生）
 * @param title       标题（取 md 首个 # 标题，否则用文件名）
 * @param rawMarkdown 原始 markdown 正文
 * @param sourceFile  来源文件相对路径
 * @param createdAt   读取时间
 */
public record ContentInput(
        String id,
        String title,
        String rawMarkdown,
        String sourceFile,
        Instant createdAt
) {
}
