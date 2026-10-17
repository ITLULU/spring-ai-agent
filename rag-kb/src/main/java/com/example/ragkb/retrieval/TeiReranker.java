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
