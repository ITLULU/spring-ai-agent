package com.example.ragkb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 测试集守门测试：qa_v2 全部锚点必须在源文档中可定位——
 * 这个测试失败 = 有人改了文档却没改题（SOP① 的自动化形态）。
 */
class DatasetServiceTest {

    private DatasetService svc;

    @BeforeEach
    void setUp() {
        svc = new DatasetService();
        svc.setDatasetPath("data/eval/qa_v2/qa_v2.json");
        svc.setDocsDir("data/docs");
    }

    @Test
    void qaV2AllAnchorsLocatable() throws Exception {
        List<QaCase> cases = svc.loadCases();
        assertFalse(cases.isEmpty());
        Map<String, DatasetService.ValidationItem> report = svc.validate(cases);
        List<String> stale = report.entrySet().stream()
                .filter(e -> !e.getValue().ok())
                .map(e -> e.getKey() + ":" + e.getValue().reason())
                .toList();
        assertEquals(List.of(), stale, "存在锚点定位失败的题目");
    }

    @Test
    void caseIdsUnique() throws Exception {
        List<QaCase> cases = svc.loadCases();
        assertEquals(cases.size(), cases.stream().map(QaCase::caseId).distinct().count());
        assertTrue(cases.stream().anyMatch(QaCase::heldOut), "必须存在 held-out 题");
        assertTrue(cases.stream().anyMatch(c -> "adversarial".equals(c.category())), "必须存在对抗题");
    }

    @Test
    void staleAnchorDetected() {
        QaCase broken = new QaCase("x-1", "q", "a", "attendance_policy.md",
                "这段文字不存在于文档", "也不存在", false, false, "fact");
        DatasetService.ValidationItem v = svc.validateOne(broken);
        assertFalse(v.ok());
        assertEquals("ANCHOR_START_STALE", v.reason());
    }

    @Test
    void evidenceHitUsesProbeAgainstChunk() {
        QaCase c = new QaCase("att-001", "q", "10 天", "attendance_policy.md",
                "员工入职满 1 年享有 5 天带薪年假", "满 5 年享有 15 天", false, false, "fact");
        String content = "## 年假\n员工入职满 1 年享有 5 天带薪年假，满 3 年享有 10 天，满 5 年享有 15 天。";
        RetrievedChunkShim.hit(svc, c, content);
    }

    /** 小工具：构造 RetrievedChunk 验证 evidenceHit 的空白/换行容忍 */
    private static class RetrievedChunkShim {
        static void hit(DatasetService svc, QaCase c, String content) {
            var chunk = new com.example.ragkb.retrieval.RetrievedChunk(
                    "c-1", content, content, "attendance_policy.md", 0.5, 3.0, 0.03, null);
            assertTrue(svc.evidenceHit(c, chunk), "归一化后应命中证据探针");
            var other = new com.example.ragkb.retrieval.RetrievedChunk(
                    "c-2", "完全无关的内容", "完全无关的内容", "expense_policy.md", 0.5, 3.0, 0.03, null);
            assertFalse(svc.evidenceHit(c, other), "跨文档不应命中");
        }
    }
}
