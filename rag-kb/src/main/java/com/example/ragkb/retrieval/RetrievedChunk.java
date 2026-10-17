package com.example.ragkb.retrieval;

/**
 * 检索候选块。三种分数分别来自向量/BM25/RRF，rerankScore 为精排后填充。
 * generationContent：喂给 LLM 的文本——parent-child 策略下是 parent 全文，
 * 其它策略下等于 content。"检索单元 ≠ 生成单元"正是该策略的精髓。
 */
public record RetrievedChunk(
        String chunkId,
        String content,
        String generationContent,
        String source,
        double vectorScore,
        double bm25Score,
        double fusedScore,
        Double rerankScore) {

    public RetrievedChunk withRerank(double score) {
        return new RetrievedChunk(chunkId, content, generationContent, source,
                vectorScore, bm25Score, fusedScore, score);
    }
}
