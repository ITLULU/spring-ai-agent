package com.example.ragkb.eval;

/**
 * 评测集条目（qa_v2）。构造方法必须讲得出（面试会问"测试集哪来的"）：
 * 反向出题——先选中文档段落，再据段落出题，保证每条可溯源；
 * unanswerable=true 的题专测拒答机制。人工只审校，不主写，避免
 * 无意识地出"贴合系统强项"的题导致指标虚高。
 *
 * v2 新增字段（评测机的地基，《评测机增强实施方案.md》§2.1）：
 *  - caseId        稳定身份，跨 run 追踪错题的主键；
 *  - evidenceStart/End 证据段落锚文本（前 ~20 字符即可）。用锚文本而非字符
 *    offset：文档小改版后锚文本仍可定位，offset 会全体漂移；
 *  - heldOut       true → 永不参与 onlyFailed 重跑/调优流程，仅里程碑全量时
 *    统计，是防"teaching to the test"的诚实指标（§5.1）；
 *  - category      fact | paraphrase | trap | multiHop | refusal | adversarial。
 */
public record QaCase(
        String caseId,
        String question,
        String expectedAnswer,
        String source,
        String evidenceStart,
        String evidenceEnd,
        boolean unanswerable,
        boolean heldOut,
        String category) {
}
