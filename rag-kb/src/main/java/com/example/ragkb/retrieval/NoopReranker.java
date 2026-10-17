package com.example.ragkb.retrieval;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未启用 rerank 时直通（默认）。 */
@Component
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "false", matchIfMissing = true)
public class NoopReranker implements Reranker {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }
}
