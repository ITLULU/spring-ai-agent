package com.example.ragkb.eval;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.ragkb.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 测试集加载 / 版本指纹 / 锚点校验（《方案》§2，归因分层的地基）。
 * validate() 必须在每次全量评测前跑（SOP 第①步）：文档改版后旧题的锚文本
 * 定位不到 → 该题 QUARANTINED，不计入系统失败——防止"过期题目"驱动你去
 * 修复一个根本没坏的系统。
 * 锚点比对做空白剥离归一化：切块/抽取过程中的换行与空格漂移不应误判为过期。
 */
@Component
public class DatasetService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 证据探针取归一化锚文本前 24 字符：足够定位段落，又容忍段落内的排版差异 */
    private static final int PROBE_LEN = 24;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> docCache = new HashMap<>();

    @Value("${rag.eval.dataset:data/eval/qa_v2/qa_v2.json}")
    private String datasetPath;

    @Value("${rag.eval.docs-dir:data/docs}")
    private String docsDir;

    public List<QaCase> loadCases() throws IOException {
        return mapper.readValue(new File(datasetPath),
                mapper.getTypeFactory().constructCollectionType(List.class, QaCase.class));
    }

    /** datasetVersion = 测试集文件内容 hash 前 8 位：跨版本指标禁止对比（§5.2） */
    public String datasetVersion() {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(datasetPath));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    public static String normalize(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll("");
    }

    /** 证据探针：归一化锚文本前 PROBE_LEN 字符（静态工具，/api/retrieve 调试端点复用同一口径） */
    public static String probeOf(String anchor) {
        String s = normalize(anchor);
        return s.substring(0, Math.min(PROBE_LEN, s.length()));
    }

    /** 证据探针：归一化 evidenceStart 的前 PROBE_LEN 字符 */
    public String evidenceProbe(QaCase c) {
        return probeOf(c.evidenceStart());
    }

    /** 归因 Q1 判定：检索到的块（含 parent 全文）是否覆盖证据段落起始 */
    public boolean evidenceHit(QaCase c, RetrievedChunk chunk) {
        if (c.unanswerable() || c.evidenceStart() == null || c.evidenceStart().isBlank()) {
            return false;
        }
        if (c.source() != null && !c.source().isBlank() && !c.source().equals(chunk.source())) {
            return false;
        }
        String probe = evidenceProbe(c);
        return !probe.isEmpty()
                && (normalize(chunk.generationContent()).contains(probe)
                        || normalize(chunk.content()).contains(probe));
    }

    public record ValidationItem(String caseId, boolean ok, String reason) {
    }

    /**
     * 逐题校验锚点在源文档中可定位。返回 caseId → 校验项；
     * ok=false 的题在 run 中被隔离（verdict=QUARANTINED），不消耗 LLM 调用。
     */
    public Map<String, ValidationItem> validate(List<QaCase> cases) {
        Map<String, ValidationItem> report = new LinkedHashMap<>();
        for (QaCase c : cases) {
            report.put(c.caseId(), validateOne(c));
        }
        return report;
    }

    public ValidationItem validateOne(QaCase c) {
        if (c.caseId() == null || c.caseId().isBlank()) {
            return new ValidationItem(c.caseId(), false, "CASE_ID_MISSING");
        }
        if (c.unanswerable()) {
            return new ValidationItem(c.caseId(), true, "REFUSAL_CASE_NO_ANCHOR");
        }
        if (c.evidenceStart() == null || c.evidenceStart().isBlank()
                || c.evidenceEnd() == null || c.evidenceEnd().isBlank()) {
            return new ValidationItem(c.caseId(), false, "ANCHORS_MISSING");
        }
        if (c.source() == null || c.source().isBlank()) {
            return new ValidationItem(c.caseId(), false, "SOURCE_MISSING");
        }
        String doc;
        try {
            doc = documentNormalized(c.source());
        } catch (IOException | UncheckedIOException e) {
            return new ValidationItem(c.caseId(), false, "DOC_NOT_FOUND:" + c.source());
        }
        int s = doc.indexOf(normalize(c.evidenceStart()));
        int e = doc.indexOf(normalize(c.evidenceEnd()));
        if (s < 0) {
            return new ValidationItem(c.caseId(), false, "ANCHOR_START_STALE");
        }
        if (e < 0) {
            return new ValidationItem(c.caseId(), false, "ANCHOR_END_STALE");
        }
        if (e < s) {
            return new ValidationItem(c.caseId(), false, "ANCHOR_ORDER_SWAPPED");
        }
        return new ValidationItem(c.caseId(), true, "OK");
    }

    /** 隔离原因列表（给 /dataset/validate 端点做人读报告） */
    public List<Map<String, Object>> validateReport(List<QaCase> cases) {
        List<Map<String, Object>> rows = new ArrayList<>();
        validate(cases).forEach((id, item) -> rows.add(Map.of(
                "caseId", id == null ? "?" : id,
                "ok", item.ok(),
                "reason", item.reason())));
        return rows;
    }

    private String documentNormalized(String source) throws IOException {
        return docCache.computeIfAbsent(source, k -> {
            try {
                return normalize(Files.readString(Path.of(docsDir, k), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** 测试注入用 */
    void setDatasetPath(String path) {
        this.datasetPath = path;
    }

    void setDocsDir(String dir) {
        this.docsDir = dir;
    }
}
