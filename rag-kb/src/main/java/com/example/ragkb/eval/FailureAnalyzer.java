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

    /**
     * 纯规则归因：按固定优先级把失败落到第一个能解释它的层，顺序本身携带诊断语义——
     * 上游（检索）没到位时不去冤枉下游（生成），这叫"故障归因的最小充分层"。
     * <ol>
     *   <li>JUDGE_ERROR → NONE：裁判自己挂了不是被测系统的错，不能计入系统失败，
     *       否则模型 API 抖动会污染指标（verdict 层已单独记 ERROR 供人工剔除）；</li>
     *   <li>拒答题（unanswerable）：真拒答=NONE（考点正是"不该答的别硬答"）；
     *       强答=FAITHFULNESS——无中生有是幻觉，归忠实度而非生成准确性；</li>
     *   <li>!evidenceHit → RETRIEVAL_MISS：标准答案的锚点证据压根没进 Top-K，
     *       后面生成再强也救不回，该去动切块/检索而不是 prompt；</li>
     *   <li>refused（可答题却拒答）→ FALSE_REFUSAL：证据召回了却说不确定，
     *       通常是对话模型置信阈值/提示词过保守，与"该拒不拒"是相反方向的病；</li>
     *   <li>Judge 判不服 → GENERATION_ERROR：检索到位、也答了，但内容不对，
     *       问题在上下文组织或生成 prompt；</li>
     *   <li>!citationValid → FAITHFULNESS：内容对了但引用缺失/越界，
     *       溯源链断了——数字对但来源编造，同样是忠实度问题；</li>
     *   <li>全部通过 → NONE。</li>
     * </ol>
     * 输入全部来自 runCase 已落盘的现场快照，本函数零额外 LLM 调用（纯函数，可单测）。
     *
     * @param c             测试用例（含是否应拒答）
     * @param evidenceHit   期望证据锚点是否出现在本次检索 Top-K 中
     * @param refused       系统是否输出了拒答
     * @param judgeVerdict  LLM-as-Judge 结论（CORRECT/INCORRECT/JUDGE_ERROR/NOT_JUDGED/N/A）；
     *                      NOT_JUDGED=未调裁判（误拒答短路），归因优先看 refused 标志
     * @param citationValid 引用后验检查是否通过
     * @return FailureLayer 枚举名字符串，写入 JSONL 供错题本按层聚合
     */
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
