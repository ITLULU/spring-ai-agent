package com.example.ragkb.eval;

/**
 * 失败归因分层标签（《方案》§4.1，先做四档）。每条 FAIL 自带排查方向：
 *  - RETRIEVAL_MISS    证据段落根本没进 top-k → 调切块 / hybrid / top-n / rerank；
 *  - FALSE_REFUSAL     证据召回了但最佳向量分低于阈值被防线 1 误杀 → 调 min-vector-similarity；
 *  - GENERATION_ERROR  检索命中且已生成，Judge 仍判错 → 调 prompt / final-k / 模型；
 *  - FAITHFULNESS      结论判对但引用缺失/越界，或不可答题被强答（幻觉）；
 *  - QUARANTINED       题目过期（锚文本定位不到），不算系统失败，移出统计；
 *  - NONE              无错因（PASS / REFUSED_OK / Judge 自身异常）。
 */
public enum FailureLayer {
    RETRIEVAL_MISS,
    FALSE_REFUSAL,
    GENERATION_ERROR,
    FAITHFULNESS,
    QUARANTINED,
    NONE
}
