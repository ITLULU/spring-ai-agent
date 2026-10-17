package com.example.ragkb.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * TEI（Text Embeddings Inference）/rerank 协议客户端，也兼容主流托管 rerank API：
 * POST {base-url}  body: {"query": "...", "texts": ["..."]}
 * resp: {"results": [{"index": 0, "relevance_score": 0.98}, ...]}
 */
@Component
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true")
public class TeiReranker implements Reranker {

    private final RestClient restClient;
    private final String baseUrl;

    public TeiReranker(@org.springframework.beans.factory.annotation.Value("${rag.rerank.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.create();
    }

    /**
     * cross-encoder 精排：把 (query, 每块文本) 成对送 rerank 服务，token 级 attend 重新打分。
     * <p>流程与三个关键设计：
     * <ul>
     *   <li>送检文本用 content（子块/原块）而非 generationContent（父块全文）：
     *       cross-encoder 有输入长度预算，精排要的是"命中片段是否真回答得了问题"，
     *       父块多余内容只会稀释信号；</li>
     *   <li>响应用 index 回映射候选位置：rerank 服务可能任意乱序返回，
     *       分数必须贴回正确的 chunk；贴分后用 withRerank 生成新 record（不可变对象模式），
     *       原 candidates 不受污染；</li>
     *   <li>失败降级而非报错：响应缺失/无 results 时退回按融合名次截断——
     *       精排是锦上添花，不该因外部服务抖动把整个 /api/query 打成 500。</li>
     * </ul>
     * <p>成本提示（面试可讲）：每次调用 = topN 次 forward pass，所以只对召回池精排
     * 不对全库精排；这也是"先 RRF 粗筛再 rerank 精排"的两级漏斗存在的原因。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        List<String> texts = candidates.stream().map(RetrievedChunk::content).toList();
        JsonNode resp = restClient.post()
                .uri(baseUrl)
                .header("Content-Type", "application/json")
                .body(Map.of("query", query, "texts", texts))
                .retrieve()
                .body(JsonNode.class);

        List<RetrievedChunk> reranked = new ArrayList<>();
        if (resp == null || !resp.has("results")) {
            // 降级：当作 Noop 直通，保留融合名次
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
        for (JsonNode r : resp.get("results")) {
            int idx = r.get("index").asInt();
            double score = r.get("relevance_score").asDouble();
            reranked.add(candidates.get(idx).withRerank(score));
        }
        reranked.sort(Comparator.comparingDouble(RetrievedChunk::rerankScore).reversed());
        return reranked.subList(0, Math.min(topN, reranked.size()));
    }
}
