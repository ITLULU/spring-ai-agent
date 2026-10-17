package com.example.ragkb.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.ragkb.config.DataPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 评测结果的 JSONL 存取（output/eval-runs/{runId}.jsonl，《方案》§3.1/§3.2）。
 * 为什么是 JSONL 不是数据库：千级 case × 十来个 run，追加写全够用；
 * 但行结构按 case_result 表设计——档 1（十万级）迁 Postgres 时是机械工作（§8.2 B5）。
 * 逐条 append + flush 的意义：中断不丢结果，重跑时 hasCase() 跳过已完成 = 免费的断点续跑。
 */
@Component
public class EvalRunStore {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${rag.eval.runs-dir:output/eval-runs}")
    private String runsDir;

    private Path file(String runId) {
        if (runId == null || runId.contains("..") || runId.contains("/") || runId.contains("\\")) {
            throw new IllegalArgumentException("非法 runId: " + runId);
        }
        return DataPath.resolve(runsDir).resolve(runId + ".jsonl");
    }

    public boolean exists(String runId) {
        return Files.isRegularFile(file(runId));
    }

    public synchronized void writeHeader(RunHeader header) throws IOException {
        Path p = file(header.runId());
        Files.createDirectories(p.getParent());
        Files.writeString(p, mapper.writeValueAsString(header) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public synchronized void appendCase(String runId, CaseResult result) {
        try {
            Files.writeString(file(runId),
                    mapper.writeValueAsString(result) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("评测结果落盘失败: " + runId, e);
        }
    }

    /** run 末汇总行（§3.1 schema 的 type:summary）：归档用，读取时重算不依赖它 */
    public synchronized void appendSummary(String runId, Map<String, Object> summary) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "summary");
            line.putAll(summary);
            Files.writeString(file(runId),
                    mapper.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("汇总行落盘失败: " + runId, e);
        }
    }

    public List<String> listRunIds() throws IOException {
        Path dir = DataPath.resolve(runsDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var paths = Files.list(dir)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .map(n -> n.substring(0, n.length() - 6))
                    .sorted()
                    .toList();
        }
    }

    public RunHeader readHeader(String runId) throws IOException {
        List<String> lines = readLines(runId);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        return mapper.readValue(lines.get(0), RunHeader.class);
    }

    public List<CaseResult> readCases(String runId) {
        try {
            return readLines(runId).stream()
                    .filter(l -> !l.isBlank())
                    .map(l -> {
                        try {
                            JsonNode node = mapper.readTree(l);
                            return "case".equals(node.path("type").asText())
                                    ? mapper.readValue(l, CaseResult.class) : null;
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(r -> r != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 断点续跑：该 run 里这条 case 是否已有结果行 */
    public boolean hasCase(String runId, String caseId) {
        if (!exists(runId)) {
            return false;
        }
        try {
            return readLines(runId).stream().anyMatch(l -> {
                try {
                    JsonNode n = mapper.readTree(l);
                    return "case".equals(n.path("type").asText()) && caseId.equals(n.path("caseId").asText());
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> readLines(String runId) throws IOException {
        Path p = file(runId);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    /** 汇总：主集与 held-out 分开统计（§5.1），并输出错因分布 */
    public Map<String, Object> summarize(List<CaseResult> cases) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("done", cases.size());
        out.put("main", summarizeSet(cases.stream().filter(c -> !c.heldOut()).toList()));
        out.put("heldOut", summarizeSet(cases.stream().filter(CaseResult::heldOut).toList()));
        Map<String, Long> byLayer = cases.stream()
                .filter(c -> c.isFail() && !"NONE".equals(c.failureLayer()))
                .collect(Collectors.groupingBy(CaseResult::failureLayer, LinkedHashMap::new, Collectors.counting()));
        out.put("failureLayerCounts", byLayer);
        long tokenSum = cases.stream()
                .map(c -> c.tokens() == null ? CaseResult.TokenUsage.NONE : c.tokens())
                .mapToLong(CaseResult.TokenUsage::total).sum();
        out.put("totalTokens", tokenSum);
        return out;
    }

    private Map<String, Object> summarizeSet(List<CaseResult> set) {
        Map<String, Object> m = new LinkedHashMap<>();
        long quarantined = set.stream().filter(c -> CaseResult.QUARANTINED.equals(c.verdict())).count();
        long pass = set.stream().filter(c -> CaseResult.PASS.equals(c.verdict())).count();
        long fail = set.stream().filter(c -> CaseResult.FAIL.equals(c.verdict())).count();
        long refusedOk = set.stream().filter(c -> CaseResult.REFUSED_OK.equals(c.verdict())).count();
        long hallucinated = set.stream().filter(c -> CaseResult.HALLUCINATED.equals(c.verdict())).count();
        long error = set.stream().filter(c -> CaseResult.ERROR.equals(c.verdict())).count();
        long answerable = pass + fail;
        long unanswerable = refusedOk + hallucinated;
        m.put("total", set.size());
        m.put("quarantined", quarantined);
        m.put("answerAccuracy", answerable == 0 ? "N/A" : pct(pass, answerable));
        m.put("refusalAccuracy", unanswerable == 0 ? "N/A" : pct(refusedOk, unanswerable));
        m.put("error", error);
        m.put("pass", pass);
        m.put("fail", fail);
        m.put("refusedOk", refusedOk);
        m.put("hallucinated", hallucinated);
        return m;
    }

    private String pct(long a, long b) {
        return String.format("%.1f%%", 100.0 * a / b);
    }

    /** Markdown 报告由 JSONL 渲染生成——报告是数据视图，不是数据本身（§3.1） */
    public String renderReport(String runId) throws IOException {
        RunHeader h = readHeader(runId);
        List<CaseResult> cases = readCases(runId);
        StringBuilder md = new StringBuilder("# RAG 评测报告 ").append(runId).append("\n\n## Run 快照\n\n");
        md.append("- datasetVersion: ").append(h.datasetVersion()).append('\n');
        md.append("- parentRun: ").append(h.parentRun() == null ? "-" : h.parentRun()).append('\n');
        md.append("- startedAt: ").append(h.startedAt()).append("  total: ").append(h.total()).append('\n');
        md.append("- config: ").append(h.config()).append('\n');
        md.append("- models: ").append(h.models()).append('\n');
        md.append("\n## 汇总\n\n");
        for (Map.Entry<String, Object> e : summarize(cases).entrySet()) {
            md.append("- **").append(e.getKey()).append("**: ").append(e.getValue()).append('\n');
        }
        md.append("\n## 明细\n\n| caseId | 判定 | 错因 | heldOut | 引用有效 | 时延(ms) | 问题 |\n|---|---|---|---|---|---|---|\n");
        for (CaseResult c : cases) {
            md.append("| ").append(c.caseId())
                    .append(" | ").append(c.verdict())
                    .append(" | ").append(c.failureLayer())
                    .append(" | ").append(c.heldOut())
                    .append(" | ").append(c.citationValid())
                    .append(" | ").append(c.latencyMs())
                    .append(" | ").append(escape(c.question())).append(" |\n");
        }
        return md.toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** 测试注入用 */
    void setRunsDir(String dir) {
        this.runsDir = dir;
    }
}
