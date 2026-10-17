package com.example.ragkb.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Judge 失误清单（《方案》§4.2）——替代正式 kappa 校准的轻量做法：
 * 人工复核失败 case 时发现"Judge 误判"就 POST 记录一条，沉淀到 judge-errors.md。
 * 用途有两个：修 Judge prompt（典型误判作为反例追加）；
 * 也是"评测集反哺"的雏形——被记错的题往往本身有歧义。
 * 格式选 Markdown 而不是 JSON：这份清单的消费者是人（审校 Judge prompt 时通读），不是程序。
 */
@Component
public class EvalJudgeErrorLog {

    @Value("${rag.eval.judge-errors-file:data/eval/judge-errors.md}")
    private String filePath;

    public record JudgeErrorEntry(String runId, String caseId, String judgeVerdict,
                                  String humanVerdict, String note) {
    }

    public synchronized String append(JudgeErrorEntry e) throws IOException {
        Path p = Path.of(filePath);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        if (!Files.isRegularFile(p)) {
            Files.writeString(p, """
                    # Judge 失误清单

                    人工复核评测失败 case 时记录的裁判误判。每攒约 10 条，
                    把典型误判作为 few-shot 反例追加进 Judge prompt，并跑 smoke 验证裁判行为变化（方案 §4.2）。

                    """, StandardCharsets.UTF_8);
        }
        String line = "- **%s** | run=%s case=%s | Judge判=%s → 人判=%s | 备注: %s%n"
                .formatted(LocalDate.now(), e.runId(), e.caseId(),
                        e.judgeVerdict(), e.humanVerdict(),
                        e.note() == null ? "" : e.note().replace("\n", " "));
        Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return p.toString();
    }

    public String read() {
        try {
            Path p = Path.of(filePath);
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8)
                    : "# Judge 失误清单\n\n（暂无记录）\n";
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 测试注入用 */
    void setFilePath(String path) {
        this.filePath = path;
    }
}
