package com.example.ragkb.qa;

import java.util.List;

/**
 * 对外问答响应。refused=true 表示触发拒答；citations 为引用溯源列表。
 * promptTokens/completionTokens 供"效果-成本"叙事记账（《方案》§3.1 tokens 字段）；
 * -1 表示模型未返回 usage。注意：查询 embedding 的 token 发生在 VectorStore 内部，
 * 此处不可见——成本口径要讲清楚边界。
 */
public record QueryResponse(
        String answer,
        boolean refused,
        List<Citation> citations,
        long elapsedMs,
        int promptTokens,
        int completionTokens) {

    /**
     * 固定拒答语——全局唯一定义处：防线 1（检索层）、防线 2（提示层要求原样输出）、
     * 拒答识别（startsWith）与裁判短路全部引用本常量，改文案只改这一处，
     * 不存在"提示词让模型输出 A、代码在比 B"的双口径漂移。
     */
    public static final String REFUSAL = "无法回答";

    /**
     * 一条可回查的引用：no 对应提示词里的 [n]，chunkId 可回 ChunkStore 取完整原文，
     * snippet 取子块 content 前 120 字——忠实度评测（答案句是否真被引用块支撑）
     * 的校验锚点就是它。
     */
    public record Citation(int no, String chunkId, String source, String snippet) {
    }
}
