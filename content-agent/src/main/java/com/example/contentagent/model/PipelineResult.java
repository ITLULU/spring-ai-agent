package com.example.contentagent.model;

import java.time.Instant;

/**
 * 一次 pipeline 运行的聚合结果：串起输入、分析、变现方案、两篇稿件。
 */
public record PipelineResult(
        ContentInput input,
        ContentAnalysis analysis,
        MonetizationPlan monetizationPlan,
        PlatformArticle zhihuArticle,
        PlatformArticle wechatArticle,
        Instant finishedAt
) {
}
