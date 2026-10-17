package com.example.contentagent.model;

import java.util.List;

/**
 * 变现方案：由 MonetizationAgent 基于 ContentAnalysis 产出。
 */
public record MonetizationPlan(
        String planTitle,               // 方案标题
        String oneLiner,                // 一句话定位
        List<RevenueChannel> channels,  // 变现渠道明细
        List<String> nextActions,       // 可执行的下一步动作（按优先级）
        String difficulty,              // 启动难度：低 / 中 / 高
        String timeToFirstRevenue       // 预计多久见到第一笔收入
) {
    /**
     * 单个变现渠道。
     *
     * @param name        渠道名（如：模板销售 / 企业知识库交付 / 求职咨询）
     * @param description 具体怎么做
     * @param pricing     建议定价
     * @param startupCost 启动成本
     * @param potential   收入潜力（区间）
     */
    public record RevenueChannel(
            String name,
            String description,
            String pricing,
            String startupCost,
            String potential
    ) {
    }
}
