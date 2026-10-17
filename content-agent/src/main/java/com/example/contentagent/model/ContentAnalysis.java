package com.example.contentagent.model;

import java.util.List;

/**
 * 内容分析结果：从原始 markdown 中提炼出的可变现要素。
 * 由 ContentAnalysisAgent 产出，供后续写作与变现 Agent 使用。
 */
public record ContentAnalysis(
        String coreTopic,           // 一句话核心主题
        List<String> technicalPoints, // 技术要点清单
        List<String> uniqueAngles,    // 差异化角度（只有作者能写的部分）
        List<String> targetAudience,  // 目标读者画像
        List<String> painPoints,      // 读者痛点 / 好奇钩子
        String monetizationHook,      // 变现钩子（可引流的产品/服务）
        List<String> seoKeywords      // 搜索关键词
) {
}
