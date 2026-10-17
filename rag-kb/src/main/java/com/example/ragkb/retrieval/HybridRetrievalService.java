package com.example.ragkb.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ragkb.chunking.Chunk;
import com.example.ragkb.store.ChunkStore;

/**
 * 混合检索编排：向量 TopN + BM25 TopN → RRF 融合 → 可选 Rerank → TopK。
 * 每路分数都保留在 RetrievedChunk 上，评测时能归因（是召回问题还是排序问题）。
 */
@Service
public class HybridRetrievalService {

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final ChunkStore chunkStore;
    private final Reranker reranker;

    @Value("${rag.retrieval.top-n:20}")
    private int topN;

    @Value("${rag.retrieval.final-k:5}")
    private int finalK;

    @Value("${rag.retrieval.hybrid:true}")
    private boolean hybrid;

    public HybridRetrievalService(VectorStore vectorStore, Bm25Index bm25Index,
                                  ChunkStore chunkStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.chunkStore = chunkStore;
        this.reranker = reranker;
    }

    public List<RetrievedChunk> retrieve(String question) {
        // 1. 向量召回（语义）
        List<Document> vectorDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topN).build());
        Map<String, Double> vectorScores = new HashMap<>();
        List<String> vectorIds = new ArrayList<>();
        for (Document d : vectorDocs) {
            vectorIds.add(d.getId());
            Double s = d.getScore();
            vectorScores.put(d.getId(), s == null ? 0 : s);
        }

        // 2. BM25 召回（词法）
        Map<String, Double> bm25Scores = new HashMap<>();
        List<String> bm25Ids = new ArrayList<>();
        if (hybrid) {
            for (Bm25Index.Hit hit : bm25Index.search(question, topN)) {
                bm25Ids.add(hit.chunkId());
                bm25Scores.put(hit.chunkId(), hit.score());
            }
        }

        // 3. RRF 融合（保序：分数降序）
        Map<String, Double> fused = hybrid
                ? RrfFusion.fuse(vectorIds, bm25Ids)
                : RrfFusion.fuse(vectorIds);

        // 4. 回取原文，装配 RetrievedChunk；parent-child 时生成内容取 parent 全文
        List<RetrievedChunk> candidates = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            Chunk chunk = chunkStore.get(e.getKey());
            if (chunk == null) {
                continue;
            }
            Object parent = chunk.metadata().get("parentContent");
            candidates.add(new RetrievedChunk(
                    chunk.id(), chunk.content(),
                    parent != null ? parent.toString() : chunk.content(),
                    chunk.source(),
                    vectorScores.getOrDefault(chunk.id(), 0.0),
                    bm25Scores.getOrDefault(chunk.id(), 0.0),
                    e.getValue(), null));
        }

        // 5. Rerank 精排（未启用时 Noop 直通截断）
        return reranker.rerank(question, candidates, finalK);
    }

    /** 最佳向量相似度，供拒答判断用 */
    public double bestVectorScore(List<RetrievedChunk> chunks) {
        return chunks.stream().mapToDouble(RetrievedChunk::vectorScore).max().orElse(0.0);
    }
}
