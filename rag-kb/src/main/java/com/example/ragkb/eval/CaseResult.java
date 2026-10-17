package com.example.ragkb.eval;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 单条 case 在一个 run 中的完整现场（JSONL 一行，《方案》§3.1）。
 * 设计成"表的一行"：caseId 可查、可 diff、可 onlyFailed 重跑；
 * retrieval 快照让二次排查不用复现现场。
 * verdict 取值：PASS | FAIL | REFUSED_OK | HALLUCINATED | QUARANTINED | ERROR。
 */
public record CaseResult(
        String type,
        String caseId,
        String category,
        boolean heldOut,
        String verdict,
        String failureLayer,
        String question,
        String expectedAnswer,
        String answer,
        boolean refused,
        boolean citationValid,
        List<RetrievalRow> retrieval,
        String judgeVerdict,
        long latencyMs,
        TokenUsage tokens) {

    public static final String TYPE = "case";

    /** 判定为"错题"的三类：FAIL/HALLUCINATED/ERROR 进 onlyFailed 重跑队列；QUARANTINED 不进。
     * @JsonIgnore：isXxx 会被 Jackson 当成属性序列化，反序列化时会因未知字段报错，必须排除 */
    @JsonIgnore
    public boolean isFail() {
        return switch (verdict) {
            case FAIL, HALLUCINATED, ERROR -> true;
            default -> false;
        };
    }

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String REFUSED_OK = "REFUSED_OK";
    public static final String HALLUCINATED = "HALLUCINATED";
    public static final String QUARANTINED = "QUARANTINED";
    public static final String ERROR = "ERROR";

    /** 检索快照行：三路分数 + 证据段落是否命中（归因 Q1 的判定依据）。 */
    public record RetrievalRow(
            String chunkId,
            String source,
            double vectorScore,
            double bm25Score,
            double fusedScore,
            boolean evidenceHit) {
    }

    /**
     * 每题成本记账（《方案》§3.1 tokens 字段，"效果-成本"叙事的数据源）：
     * 生成上下文/补全 + 裁判上下文/补全；-1 表示模型未返回 usage。
     * 口径边界：查询 embedding 的消耗在 VectorStore 内部，不可见，不计入。
     */
    public record TokenUsage(int prompt, int completion, int judgePrompt, int judgeCompletion) {

        public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

        public int total() {
            return Math.max(0, prompt) + Math.max(0, completion)
                    + Math.max(0, judgePrompt) + Math.max(0, judgeCompletion);
        }
    }
}
