package com.example.ragkb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Judge 失误清单归档 + 证据探针静态口径测试。
 */
class EvalJudgeErrorLogTest {

    @TempDir
    Path tmp;

    @Test
    void appendCreatesHeaderAndEntry() throws Exception {
        EvalJudgeErrorLog log = new EvalJudgeErrorLog();
        String file = tmp.resolve("judge-errors.md").toString();
        log.setFilePath(file);

        String savedTo = log.append(new EvalJudgeErrorLog.JudgeErrorEntry(
                "run-1", "att-001", "INCORRECT", "CORRECT", "答案语义等价，Judge 苛判"));

        assertTrue(java.nio.file.Files.isRegularFile(Path.of(savedTo)));
        String content = log.read();
        assertTrue(content.startsWith("# Judge 失误清单"));
        assertTrue(content.contains("att-001"));
        assertTrue(content.contains("Judge 苛判"));
    }

    @Test
    void probeNormalizesWhitespaceAndCapsLength() {
        // 锚文本中的空格/换行应被剥离后参与命中判定；探针截断到 24 归一化字符
        String anchor = "员工入职满 1 年享有\n5 天带薪年假，满 3 年享有 10 天";
        String probe = DatasetService.probeOf(anchor);
        // 归一化后全长恰为 24 字符，不被截断
        assertEquals(24, probe.length());
        assertTrue(probe.startsWith("员工入职满1年享有5天带薪年假"));

        String longAnchor = "一".repeat(40);
        assertEquals(24, DatasetService.probeOf(longAnchor).length());
    }

    @Test
    void probeHitsAcrossNormalizationBoundary() {
        // 文档端多一个空格/带标题前缀不应导致 miss（评测机与调试端点共用该口径）
        String probe = DatasetService.probeOf("员工入职满 1 年享有 5 天带薪年假");
        String chunk = "## 年假\n员工入职满  1 年享有 5 天带薪年假，满 3 年享有 10 天。";
        assertTrue(DatasetService.normalize(chunk).contains(probe));
    }
}
