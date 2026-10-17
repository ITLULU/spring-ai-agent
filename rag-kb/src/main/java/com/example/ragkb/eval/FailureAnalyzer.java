package com.example.ragkb.eval;

/**
 * 失败归因分层（《方案》§4.1）：把"错在哪一层"变成自动标签，
 * 让错题本天然带排查方向——RETRIEVAL_MISS 去动切块/混合检索，
 * GENERATION_ERROR 去动 prompt，而不是对着总分瞎调。
 * 规则全部基于 run 现场已有数据（三路分数/拒答标志/Judge 结论），零额外 LLM 调用。
 * 判定顺序即优先级：先判"证据根本没召回"，再判"召回了却被拒答误杀"……
 */
public final class FailureAnalyzer {

    private FailureAnalyzer() {
    }

    public static String analyze(QaCase c, boolean evidenceHit, boolean refused,
                                 String judgeVerdict, boolean citationValid) {
        // Judge 自身异常不是系统失败，错因记 NONE（verdict 层已置 ERROR）
        if ("JUDGE_ERROR".equals(judgeVerdict)) {
            return FailureLayer.NONE.name();
        }
        if (c.unanswerable()) {
            // 拒答题：正确拒答=NONE；强答=幻觉（忠实度问题）
            return refused ? FailureLayer.NONE.name() : FailureLayer.FAITHFULNESS.name();
        }
        if (!evidenceHit) {
            return FailureLayer.RETRIEVAL_MISS.name();
        }
        if (refused) {
            return FailureLayer.FALSE_REFUSAL.name();
        }
        if (!"CORRECT".equals(judgeVerdict)) {
            return FailureLayer.GENERATION_ERROR.name();
        }
        if (!citationValid) {
            return FailureLayer.FAITHFULNESS.name();
        }
        return FailureLayer.NONE.name();
    }
}
