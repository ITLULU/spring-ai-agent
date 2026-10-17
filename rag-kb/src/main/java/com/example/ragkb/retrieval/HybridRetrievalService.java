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

    /** 每路召回数（粗排池子大小）：宁多勿少，精排/融合后再收窄 */
    @Value("${rag.retrieval.top-n:20}")
    private int topN;

    /** 最终喂给 LLM 的上下文块数：越大生成质量越好但 token 成本线性增长 */
    @Value("${rag.retrieval.final-k:5}")
    private int finalK;

    /** false → 纯向量对照组：单变量实验开关，评测机靠它对量化"BM25 路到底赚多少" */
    @Value("${rag.retrieval.hybrid:true}")
    private boolean hybrid;

    public HybridRetrievalService(VectorStore vectorStore, Bm25Index bm25Index,
                                  ChunkStore chunkStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.chunkStore = chunkStore;
        this.reranker = reranker;
    }

    /**
     * 混合检索主流程（五步管道，面试可白板画图）：
     * <ol>
     *   <li>向量召回：语义路，擅长一词多义/改写（"怎么休年假" ↔ "年假天数"），
     *       得分 [0,1]；拿 topN 个 Document 同时抽出 id 列（给 RRF 用名次）和分数表（归因用）；</li>
     *   <li>BM25 召回：词法路，擅长精确术语/编号/专名，得分无界；hybrid=false 时跳过，
     *       bm25 分数全为 0——评测 diff 就能单归因到词法贡献；</li>
     *   <li>RRF 融合：两路只交 id 顺序列表，按 1/(k+rank) 累加——两路都前排的块得分最高；</li>
     *   <li>回取原文装配：按融合名次遍历，从 ChunkStore 补回正文/来源；
     *       parent-child 策略时 generationContent 取父块全文（小块精准命中、大块完整喂 LLM），
     *       非 parent-child 时两者相同；chunk 查不到 = 向量库有残留而 ChunkStore 已清（跳之，
     *       这是内存库部分写入不一致的自愈点）；</li>
     *   <li>Rerank 精排：cross-encoder 对候选重新打分截 finalK；未启用时 Noop 直通。</li>
     * </ol>
     * 关键设计：每路的原始分数都留在 RetrievedChunk 字段上（vectorScore/bm25Score/
     * fusedScore/rerankScore），失败归因时能回答"错在哪一层"而不是只有最终名次。
     *
     * @param question 自然语言问句
     * @return 最终 Top-K 命中，按相关度降序；空库时返回空表
     */
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

    /**
     * 最佳向量相似度，供拒答判断用：取的是向量路原始分（非融合分）——
     * 语义可信度只有余弦相似度能表达；融合后的 RRF 分与"答得出答不出"无关。
     * 低于 rag.retrieval.min-vector-similarity 阈值 → QaService 直接拒答不生成，
     * 省一次 token 且消灭"无米之炊"型幻觉；调阈值就是漏答 vs 幻觉的权衡。
     */
    public double bestVectorScore(List<RetrievedChunk> chunks) {
        return chunks.stream().mapToDouble(RetrievedChunk::vectorScore).max().orElse(0.0);
    }
}
