package com.example.ragkb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 归因分层规则测试（《方案》§4.3 的"三造错"验收自动化版）：
 * 每种失败形态都必须落到唯一且正确的排查方向标签。
 */
class FailureAnalyzerTest {

    private QaCase answerable() {
        return new QaCase("t-001", "问题", "答案", "doc.md", "起点", "终点", false, false, "fact");
    }

    private QaCase unanswerable() {
        return new QaCase("t-002", "问题", "", "", "", "", true, false, "refusal");
    }

    @Test
    void evidenceNotRetrieved_isRetrievalMiss() {
        assertEquals(FailureLayer.RETRIEVAL_MISS.name(),
                FailureAnalyzer.analyze(answerable(), false, false, "INCORRECT", true));
    }

    @Test
    void evidenceHitButRefused_isFalseRefusal() {
        // 误拒答优先于生成错误判定：证据召回了却被防线 1 杀掉 → 调阈值而不是调 prompt
        assertEquals(FailureLayer.FALSE_REFUSAL.name(),
                FailureAnalyzer.analyze(answerable(), true, true, "INCORRECT", true));
    }

    @Test
    void retrievedAndWrongAnswer_isGenerationError() {
        assertEquals(FailureLayer.GENERATION_ERROR.name(),
                FailureAnalyzer.analyze(answerable(), true, false, "INCORRECT", true));
    }

    @Test
    void correctButBadCitation_isFaithfulness() {
        assertEquals(FailureLayer.FAITHFULNESS.name(),
                FailureAnalyzer.analyze(answerable(), true, false, "CORRECT", false));
    }

    @Test
    void allGood_isNone() {
        assertEquals(FailureLayer.NONE.name(),
                FailureAnalyzer.analyze(answerable(), true, false, "CORRECT", true));
    }

    @Test
    void refusalCaseRefused_isNone() {
        assertEquals(FailureLayer.NONE.name(),
                FailureAnalyzer.analyze(unanswerable(), false, true, "N/A", true));
    }

    @Test
    void refusalCaseAnswered_isHallucinationFaithfulness() {
        assertEquals(FailureLayer.FAITHFULNESS.name(),
                FailureAnalyzer.analyze(unanswerable(), false, false, "N/A", true));
    }

    @Test
    void judgeError_isNoneLayerHandledByVerdict() {
        assertEquals(FailureLayer.NONE.name(),
                FailureAnalyzer.analyze(answerable(), true, false, "JUDGE_ERROR", true));
    }
}
