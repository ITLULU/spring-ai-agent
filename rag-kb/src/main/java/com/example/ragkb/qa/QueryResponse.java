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

    public record Citation(int no, String chunkId, String source, String snippet) {
    }
}
