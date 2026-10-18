package com.example.ragkb.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.ragkb.config.DataPath;
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
    // ConcurrentHashMap：/api/dataset/validate（HTTP 线程）与 eval-run 线程可能并发读写，
    // 裸 HashMap 的 computeIfAbsent 并发扩容是真实的数据竞态隐患
    private final Map<String, String> docCache = new ConcurrentHashMap<>();

    @Value("${rag.eval.dataset:data/eval/qa_v2/qa_v2.json}")
    private String datasetPath;

    @Value("${rag.eval.docs-dir:data/docs}")
    private String docsDir;

    /**
     * 加载全量测试集（JSON 数组 → List&lt;QaCase&gt;）。
     * 路径统一过 DataPath 解析——多模块启动时 CWD 是聚合根而不是 rag-kb，
     * 直接相对路径会 NoSuchFileException（历史坑）。读失败抛 IOException
     * 由 startRun 包成 IllegalStateException：测试集加载不了就不该开始评测。
     */
    public List<QaCase> loadCases() throws IOException {
        return mapper.readValue(DataPath.resolve(datasetPath).toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, QaCase.class));
    }

    /**
     * datasetVersion = 测试集文件内容 SHA-256 前 4 字节（8 个十六进制字符）。
     * 设计要点：指纹只取自"文件字节内容"，不碰路径/时间戳——换台机器、重命名
     * 目录都不应影响版本判定；而题目增删改一字即换版。diff 时两侧版本不同
     * 直接拒绝对比：换考卷后的分数和换考卷前没有可比性（§5.2 指标可比性）。IO 失败
     * 降级返回 "unknown"：版本拿不到不能阻断评测主流程，宁可少一层保护。
     */
    public String datasetVersion() {
        try {
            byte[] bytes = Files.readAllBytes(DataPath.resolve(datasetPath));
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

    /**
     * 归一化：删掉所有空白字符（\s+ → 空串），null 安全。
     * 目的：锚点比对不受换行/缩进/全半角空格排版漂移影响——文档重新格式化
     * 不应被误判为"题目过期"。代价是极短锚点可能跨段落假匹配，由 PROBE_LEN
     * 取 24 字符的长度控制兼顾区分度。
     */
    public static String normalize(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll("");
    }

    /**
     * 证据探针：归一化锚文本前 PROBE_LEN（24）字符。
     * 取"前缀"而非全文：只要求段落开头能对上，容忍段落内部后续改写/截断；
     * 取 24 字符：中文 24 字足够定位到唯一段落，又不会长到被排版差异击穿。
     * 静态工具：/api/retrieve 调试端点复用同一口径，保证人工验证与评测机说同一套话。
     */
    public static String probeOf(String anchor) {
        String s = normalize(anchor);
        return s.substring(0, Math.min(PROBE_LEN, s.length()));
    }

    /** 证据探针：归一化 evidenceStart 的前 PROBE_LEN 字符 */
    public String evidenceProbe(QaCase c) {
        return probeOf(c.evidenceStart());
    }

    /**
     * 归因 Q1（"证据到底召回没有"）的判定：本次检索的某块是否覆盖了
     * 标准答案的证据段落起始。两道前置过滤：
     * <ul>
     *   <li>拒答题/无锚文本 → 永远 false：这类题的考点不是检索命中；</li>
     *   <li>source 不一致 → 直接否：防止"碰巧同一段文字出现在另一文档"的假命中。</li>
     * </ul>
     * 匹配双通道：generationContent（parent-child 时的父块全文）或 content（子块）
     * 任一包含探针即命中——子块召回、父块送生成，两种粒度都算数。
     * 这是 FailureAnalyzer 区分 RETRIEVAL_MISS 与 GENERATION_ERROR 的唯一依据：
     * 没命中→题目答案根本不在上下文里，怪检索；命中了还错→怪生成。
     */
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
     * 整批校验（评测 SOP 第①步）：返回 caseId → 校验项，LinkedHashMap 保持题序
     * 便于人读报告。调用方（EvalService.execute）在消耗任何 LLM token 之前先跑
     * 本方法，把锚点失效的题隔离成 QUARANTINED 行——过期题目驱动你去修
     * 一个根本没坏的系统，是评测机最隐蔽的浪费。
     */
    public Map<String, ValidationItem> validate(List<QaCase> cases) {
        Map<String, ValidationItem> report = new LinkedHashMap<>();
        for (QaCase c : cases) {
            report.put(c.caseId(), validateOne(c));
        }
        return report;
    }

    /**
     * 单题校验：按固定顺序拦截五类病，错误码直接进隔离报告：
     * <ol>
     *   <li>CASE_ID_MISSING：没 id 连落盘都做不到，最先拦；</li>
     *   <li>拒答题：REFUSAL_CASE_NO_ANCHOR 直接算 ok——它的考点是"不该答的别硬答"，
     *       本来就不需要锚点；</li>
     *   <li>ANCHORS_MISSING / SOURCE_MISSING：题目结构不完整，无法验证；</li>
     *   <li>DOC_NOT_FOUND：源文档被删/改名，题目悬空；</li>
     *   <li>ANCHOR_START_STALE / ANCHOR_END_STALE：文档改版后锚文本定位不到
     *       （"过期题目"的主命中路径）；</li>
     *   <li>ANCHOR_ORDER_SWAPPED：锚尾出现在锚头之前——同样的文本被搬了位置，
     *       内容还在但语境已变，不能信任。</li>
     * </ol>
     * 全部比对在 normalize 后的文档上进行，与 evidenceHit 的命中判定同一口径。
     */
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

    /**
     * 隔离原因列表（给 /dataset/validate 端点做人读报告）：只把三列摊平成行，
     * 判断逻辑全部复用 validate，避免两套校验口径漂移（报告是视图不是权威）。
     */
    public List<Map<String, Object>> validateReport(List<QaCase> cases) {
        List<Map<String, Object>> rows = new ArrayList<>();
        validate(cases).forEach((id, item) -> rows.add(Map.of(
                "caseId", id == null ? "?" : id,
                "ok", item.ok(),
                "reason", item.reason())));
        return rows;
    }

    /**
     * 读源文档并归一化，按 source 缓存：validate 整批几十题往往集中在少数文档，
     * 每题重读磁盘+正则替换是纯浪费。缓存不失效是有意取舍：run 是短生命周期
     * 批处理，文档改了重启应用即可（与 IngestService 的重新入库动作天然同步）。
     * IOException 包成 UncheckedIOException 配合 computeIfAbsent（lambda 内不能抛检变），
     * 调用方 validateOne 接住后转 DOC_NOT_FOUND 隔离码。
     */
    private String documentNormalized(String source) throws IOException {
        return docCache.computeIfAbsent(source, k -> {
            try {
                return normalize(Files.readString(DataPath.resolve(docsDir).resolve(k), StandardCharsets.UTF_8));
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
