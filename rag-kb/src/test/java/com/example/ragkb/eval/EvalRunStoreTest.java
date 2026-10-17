package com.example.ragkb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSONL 存取 + 断点续跑标记 + 汇总的回归测试。
 */
class EvalRunStoreTest {

    @TempDir
    Path tmp;

    private EvalRunStore store() {
        EvalRunStore s = new EvalRunStore();
        s.setRunsDir(tmp.toString());
        return s;
    }

    private CaseResult caseResult(String caseId, String verdict, String layer, boolean heldOut) {
        return new CaseResult(CaseResult.TYPE, caseId, "fact", heldOut, verdict, layer,
                "问题?", "答案", "系统答案", false, true,
                List.of(new CaseResult.RetrievalRow("c1", "doc.md", 0.5, 3.2, 0.03, true)),
                "CORRECT", 100, new CaseResult.TokenUsage(1200, 80, 300, 1));
    }

    @Test
    void headerAndCasesRoundTrip() throws IOException {
        EvalRunStore s = store();
        String runId = "test-run";
        s.writeHeader(new RunHeader(RunHeader.TYPE, runId, "v1", "now", null, false, 3,
                Map.of("hybrid", true), Map.of("judge", "m")));
        s.appendCase(runId, caseResult("a-001", CaseResult.PASS, "NONE", false));
        s.appendCase(runId, caseResult("a-002", CaseResult.FAIL, "RETRIEVAL_MISS", false));
        s.appendCase(runId, caseResult("a-003", CaseResult.PASS, "NONE", true));

        assertEquals("v1", s.readHeader(runId).datasetVersion());
        List<CaseResult> cases = s.readCases(runId);
        assertEquals(3, cases.size());
        assertEquals(CaseResult.FAIL, cases.get(1).verdict());
        assertTrue(cases.get(1).isFail());
        assertFalse(cases.get(0).isFail());

        // 断点续跑标记
        assertTrue(s.hasCase(runId, "a-002"));
        assertFalse(s.hasCase(runId, "a-999"));

        // 主集 / held-out 分开统计
        Map<String, Object> sum = s.summarize(cases);
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) sum.get("main");
        @SuppressWarnings("unchecked")
        Map<String, Object> held = (Map<String, Object>) sum.get("heldOut");
        assertEquals(2, main.get("total"));
        assertEquals("50.0%", main.get("answerAccuracy"));
        assertEquals(1, held.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> layers = (Map<String, Object>) sum.get("failureLayerCounts");
        assertEquals(1L, layers.get("RETRIEVAL_MISS"));
        // token 记账：生成 1280 + 裁判 301 × 3 题
        assertEquals((long) (1200 + 80 + 300 + 1) * 3, sum.get("totalTokens"));

        // run 末汇总行：写入后仍可正常读取 case 行（summary 行不参与 case 解析）
        s.appendSummary(runId, s.summarize(cases));
        assertEquals(3, s.readCases(runId).size());
    }

    @Test
    void listRunsFindsWrittenFiles() throws IOException {
        EvalRunStore s = store();
        s.writeHeader(new RunHeader(RunHeader.TYPE, "run-x", "v1", "now", null, false, 0,
                Map.of(), Map.of()));
        assertTrue(s.listRunIds().contains("run-x"));
        assertTrue(s.exists("run-x"));
    }

    @Test
    void illegalRunIdRejected() {
        EvalRunStore s = store();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> s.exists("../escape"));
    }
}
