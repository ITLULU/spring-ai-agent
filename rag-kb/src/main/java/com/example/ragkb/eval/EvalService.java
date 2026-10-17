package com.example.ragkb.eval;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ragkb.qa.QaService;
import com.example.ragkb.qa.QueryResponse;
import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

/**
 * 评测机主编排（《方案》Step 2/3/4）——从"一次性演示脚本"升级为：
 *  - 后台单线程 run + JSONL 逐条落盘 + 断点续跑（hasCase 跳过，§3.2）；
 *  - 每题现场快照（三路分数 + evidenceHit）→ FailureAnalyzer 自动归因（§4.1）；
 *  - onlyFailedFrom 错题本二次重跑，硬编码排除 held-out（§5.1）；
 *  - diff 跨 run 对比，datasetVersion 不同直接拒绝（§5.2 指标可比性）。
 * 不引入线程池框架/数据库：千级 case 规模下，单线程 + 文件是正确成本（§0 不做清单）。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final Pattern CITE = Pattern.compile("\\[(\\d+)]");
    private static final int SMOKE_SIZE = 20;

    private final QaService qaService;
    private final HybridRetrievalService retrievalService;
    private final DatasetService datasetService;
    private final EvalJudge judge;
    private final EvalRunStore store;

    @Value("${rag.chunk.strategy:recursive}")
    private String chunkStrategy;
    @Value("${rag.retrieval.top-n:20}")
    private int topN;
    @Value("${rag.retrieval.final-k:5}")
    private int finalK;
    @Value("${rag.retrieval.hybrid:true}")
    private boolean hybrid;
    @Value("${rag.retrieval.min-vector-similarity:0.30}")
    private double minVectorSimilarity;
    @Value("${rag.rerank.enabled:false}")
    private boolean rerankEnabled;
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String chatModelName;
    @Value("${spring.ai.openai.embedding.options.model:unknown}")
    private String embedModelName;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-run");
        t.setDaemon(true);
        return t;
    });
    private volatile String activeRunId;

    public EvalService(QaService qaService, HybridRetrievalService retrievalService,
                       DatasetService datasetService, EvalJudge judge, EvalRunStore store) {
        this.qaService = qaService;
        this.retrievalService = retrievalService;
        this.datasetService = datasetService;
        this.judge = judge;
        this.store = store;
    }

    // ------------------------------------------------------------------ run

    /**
     * 提交一次评测（立即返回 runId，后台执行）。
     * onlyFailedFrom ≠ null → 错题本重跑：只跑 parent run 里 FAIL/HALLUCINATED/ERROR
     * 的题，且**硬编码排除 held-out**——调优流程永远碰不到诚实指标（§5.1）。
     */
    public synchronized String startRun(String tag, String onlyFailedFrom, boolean smoke) {
        return startRun(tag, onlyFailedFrom, smoke, null);
    }

    /**
     * resumeRunId ≠ null → 断点续跑（§3.2 验收项）：复用同一 JSONL 文件，
     * execute() 里的 hasCase 跳过已完成题；子集按原 run 头（parent/smoke）重建。
     */
    public synchronized String startRun(String tag, String onlyFailedFrom, boolean smoke, String resumeRunId) {
        if (activeRunId != null && store.exists(activeRunId)) {
            throw new IllegalStateException("已有评测在跑：" + activeRunId
                    + "，进度见 GET /api/eval/runs/" + activeRunId);
        }
        List<QaCase> all;
        try {
            all = datasetService.loadCases();
        } catch (IOException e) {
            throw new IllegalStateException("测试集读取失败: " + e.getMessage(), e);
        }
        if (resumeRunId != null && !resumeRunId.isBlank()) {
            if (!store.exists(resumeRunId)) {
                throw new IllegalArgumentException("要续跑的 run 不存在: " + resumeRunId);
            }
            RunHeader h;
            try {
                h = store.readHeader(resumeRunId);
            } catch (IOException e) {
                throw new IllegalStateException("run 头读取失败: " + e.getMessage(), e);
            }
            List<QaCase> subset = resolveSubset(all, h.parentRun(), h.smoke());
            activeRunId = resumeRunId;
            executor.submit(() -> {
                try {
                    execute(resumeRunId, subset);
                } catch (Exception e) {
                    log.error("评测 run 异常终止: {}", resumeRunId, e);
                } finally {
                    activeRunId = null;
                }
            });
            return resumeRunId;
        }
        List<QaCase> subset = resolveSubset(all, onlyFailedFrom, smoke);
        if (subset.isEmpty()) {
            throw new IllegalArgumentException(
                    "本次没有可跑的 case（检查 onlyFailedFrom 是否存在 / 测试集是否为空）");
        }
        String runId = makeRunId(tag, onlyFailedFrom);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chunkStrategy", chunkStrategy);
        config.put("topN", topN);
        config.put("finalK", finalK);
        config.put("hybrid", hybrid);
        config.put("minVectorSimilarity", minVectorSimilarity);
        config.put("rerankEnabled", rerankEnabled);
        Map<String, String> models = new LinkedHashMap<>();
        models.put("chat", chatModelName);
        models.put("embed", embedModelName);
        models.put("judge", judge.describe());
        try {
            store.writeHeader(new RunHeader(RunHeader.TYPE, runId, datasetService.datasetVersion(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    onlyFailedFrom, smoke, subset.size(), config, models));
        } catch (IOException e) {
            throw new IllegalStateException("run 头写入失败: " + e.getMessage(), e);
        }
        activeRunId = runId;
        executor.submit(() -> {
            try {
                execute(runId, subset);
            } catch (Exception e) {
                log.error("评测 run 异常终止: {}", runId, e);
            } finally {
                activeRunId = null;
            }
        });
        return runId;
    }

    /**
     * 子集解析（三选一优先级：错题本 > smoke > 全量）：
     * <ul>
     *   <li>onlyFailedFrom：读 parent run 的 JSONL，取 FAIL/HALLUCINATED/ERROR 的 caseId，
     *       并硬编码排除 held-out——调优循环永远碰不到考卷，防"用考卷题调参"的指标自欺；</li>
     *   <li>smoke：固定种子 Random(42) 抽 20 题——每次跑同一批，改一个变量后
     *       冒烟结果才可直接对比；也只在非 held-out 池里抽；</li>
     *   <li>全量：基线/最终回归用。</li>
     * </ul>
     */
    private List<QaCase> resolveSubset(List<QaCase> all, String onlyFailedFrom, boolean smoke) {
        if (onlyFailedFrom != null && !onlyFailedFrom.isBlank()) {
            Set<String> failedIds = store.readCases(onlyFailedFrom).stream()
                    .filter(r -> r.isFail() && !r.heldOut())   // §5.1：held-out 不进重跑
                    .map(CaseResult::caseId)
                    .collect(Collectors.toSet());
            return all.stream().filter(c -> failedIds.contains(c.caseId())).toList();
        }
        if (smoke) {
            // 固定种子：每次冒烟跑同一批 20 题，跨实验可比
            List<QaCase> pool = new ArrayList<>(all.stream().filter(c -> !c.heldOut()).toList());
            java.util.Collections.shuffle(pool, new Random(42));
            return pool.subList(0, Math.min(SMOKE_SIZE, pool.size()));
        }
        return all;
    }

    /**
     * runId = 时间戳-标签-短UUID：人读能排序/认标签，UUID 防同秒重名覆盖；
     * 标签先洗掉非安全字符（会直接用作 JSONL 文件名，见 EvalRunStore 的非法字符拦截）。
     */
    private String makeRunId(String tag, String onlyFailedFrom) {
        String base = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String kind = tag != null && !tag.isBlank()
                ? tag.replaceAll("[^A-Za-z0-9=_:.\\-]", "")
                : (onlyFailedFrom != null ? "retry" : "full");
        return base + "-" + kind + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    /**
     * 后台主循环（单线程，在 eval-run 线程上执行）：
     * <ol>
     *   <li>先整批 validate（锚点回原文定位）：过期题直接写 QUARANTINED 行，
     *       不消耗任何 LLM 调用——"防止过期题目驱动你去修一个没坏的系统"；</li>
     *   <li>逐题 hasCase 检查 → 已有结果跳过：这就是断点续跑的全部实现（追加写+
     *       文件即进度）；单题异常写 ERROR 行继续跑，不因一题崩全 run；</li>
     *   <li>末尾补 summary 归档行（读取时重算不依赖它，仅为人工翻文件方便）。</li>
     * </ol>
     * 注意：config 快照在 startRun 写 run 头时已固定，这里只追加 case 行——
     * 跑批中途改配置不影响已落盘行，但也不影响后续行（读的是启动时的字段值副本）。
     */
    private void execute(String runId, List<QaCase> subset) {
        Map<String, DatasetService.ValidationItem> validation = datasetService.validate(subset);
        for (QaCase c : subset) {
            if (store.hasCase(runId, c.caseId())) {
                continue;   // 断点续跑：已有结果的题直接跳过
            }
            DatasetService.ValidationItem v = validation.get(c.caseId());
            if (v != null && !v.ok()) {
                // SOP 第①步：过期题隔离，不消耗 LLM 调用，也不计入系统失败
                store.appendCase(runId, new CaseResult(CaseResult.TYPE, c.caseId(), c.category(),
                        c.heldOut(), CaseResult.QUARANTINED, FailureLayer.QUARANTINED.name(),
                        c.question(), c.expectedAnswer(), "题目校验失败: " + v.reason(),
                        false, false, List.of(), "N/A", 0, CaseResult.TokenUsage.NONE));
                continue;
            }
            try {
                runCase(runId, c);
            } catch (Exception e) {
                log.warn("case {} 执行异常: {}", c.caseId(), e.getMessage());
                store.appendCase(runId, new CaseResult(CaseResult.TYPE, c.caseId(), c.category(),
                        c.heldOut(), CaseResult.ERROR, FailureLayer.NONE.name(),
                        c.question(), c.expectedAnswer(), "EXCEPTION: " + e.getMessage(),
                        false, false, List.of(), "N/A", 0, CaseResult.TokenUsage.NONE));
            }
        }
        // run 末归档汇总行（§3.1 schema）；读取时仍以重算为准，不依赖此行
        store.appendSummary(runId, store.summarize(store.readCases(runId)));
        log.info("评测 run 完成: {} ({} cases)", runId, store.readCases(runId).size());
    }

    /**
     * 单题执行（评测机最小闭环，一次检索+一次生成+最多一次裁判）：
     * <ol>
     *   <li>检索一次两用：既落三路分数+evidenceHit 现场快照（归因依据），
     *       又复用给生成——省一半 embedding 调用，且保证"快照即生成用的那份上下文"；</li>
     *   <li>判定矩阵（四分支，面试可画表）：
     *       <pre>
     *       应拒答题 × 真拒答 → REFUSED_OK ／ × 答了 → HALLUCINATED（最严重）
     *       可回答题 × 误拒答 → FAIL（不调 Judge，拒答就是错）
     *                × 答了   → LLM-as-Judge：CORRECT→PASS / JUDGE_ERROR→ERROR / 其他→FAIL
     *       </pre>
     *       Judge 只在"双方都认同需要语义判断"的场景消耗 token，规则能定的不花钱；</li>
     *   <li>failureLayer 由 FailureAnalyzer 纯规则推导（检索没召回/误拒答/生成错），
     *       不额外调模型——归因必须比被归因的对象便宜且确定；</li>
     *   <li>整题现场（问题/期望/答案/三路分数/token 四类记账）一次性 append 落盘，
     *       flush 到 OS——中断不丢已完成题。</li>
     * </ol>
     */
    private void runCase(String runId, QaCase c) {
        long t0 = System.currentTimeMillis();

        // 检索一次，既做现场快照（归因依据）又复用给生成——不重复调 embedding
        List<RetrievedChunk> chunks = retrievalService.retrieve(c.question());
        List<CaseResult.RetrievalRow> rows = chunks.stream()
                .map(rc -> new CaseResult.RetrievalRow(rc.chunkId(), rc.source(),
                        rc.vectorScore(), rc.bm25Score(), rc.fusedScore(),
                        datasetService.evidenceHit(c, rc)))
                .toList();
        boolean evidenceHit = rows.stream().anyMatch(CaseResult.RetrievalRow::evidenceHit);

        QueryResponse resp = qaService.answer(c.question(), chunks);

        String judgeVerdict = "N/A";
        int judgePrompt = 0;
        int judgeCompletion = 0;
        String verdict;
        if (c.unanswerable()) {
            verdict = resp.refused() ? CaseResult.REFUSED_OK : CaseResult.HALLUCINATED;
        } else if (resp.refused()) {
            verdict = CaseResult.FAIL;               // 误拒答：不调 Judge，直接错
            judgeVerdict = "INCORRECT";
        } else {
            EvalJudge.Outcome outcome = judge.judge(c.question(), c.expectedAnswer(), resp.answer());
            judgeVerdict = outcome.verdict();
            judgePrompt = outcome.promptTokens();
            judgeCompletion = outcome.completionTokens();
            verdict = switch (judgeVerdict) {
                case "CORRECT" -> CaseResult.PASS;
                case "JUDGE_ERROR" -> CaseResult.ERROR;
                default -> CaseResult.FAIL;
            };
        }

        boolean citationValid = checkCitations(resp);
        String layer = FailureAnalyzer.analyze(c, evidenceHit, resp.refused(), judgeVerdict, citationValid);

        store.appendCase(runId, new CaseResult(CaseResult.TYPE, c.caseId(), c.category(),
                c.heldOut(), verdict, layer, c.question(), c.expectedAnswer(),
                resp.answer(), resp.refused(), citationValid, rows, judgeVerdict,
                System.currentTimeMillis() - t0,
                new CaseResult.TokenUsage(resp.promptTokens(), resp.completionTokens(),
                        judgePrompt, judgeCompletion)));
    }

    /** 引用后验检查：非拒答必须有引用，且 [n] 不越界（幻觉治理第一道机器校验） */
    private boolean checkCitations(QueryResponse resp) {
        if (resp.refused() || resp.answer() == null) {
            return true;
        }
        if (resp.citations().isEmpty()) {
            return false;
        }
        Matcher m = CITE.matcher(resp.answer());
        boolean any = false;
        while (m.find()) {
            any = true;
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > resp.citations().size()) {
                return false;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------- 查询

    public boolean isRunning() {
        return activeRunId != null;
    }

    public Map<String, Object> status(String runId) throws IOException {
        RunHeader h = store.readHeader(runId);
        List<CaseResult> cases = store.readCases(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("running", runId.equals(activeRunId));
        out.put("progress", cases.size() + "/" + h.total());
        out.put("header", h);
        out.put("summary", store.summarize(cases));
        return out;
    }

    public List<CaseResult> results(String runId, String verdict, String layer, String caseId) {
        return store.readCases(runId).stream()
                .filter(r -> verdict == null || verdict.isBlank() || verdict.equals(r.verdict()))
                .filter(r -> layer == null || layer.isBlank() || layer.equals(r.failureLayer()))
                .filter(r -> caseId == null || caseId.isBlank() || caseId.equals(r.caseId()))
                .toList();
    }

    public List<String> listRuns() throws IOException {
        return store.listRunIds();
    }

    public String report(String runId) throws IOException {
        return store.renderReport(runId);
    }

    /**
     * 跨 run diff（SOP 第④步）：fixed=错题修复清单；newlyFailed 不为零 → 本轮修改禁止合入。
     * datasetVersion 不同时拒绝——跨考卷的数字比较没有意义（§5.2）。
     */
    public Map<String, Object> diff(String baseRun, String headRun) throws IOException {
        RunHeader hb = store.readHeader(baseRun);
        RunHeader hh = store.readHeader(headRun);
        if (!hb.datasetVersion().equals(hh.datasetVersion())) {
            return Map.of("error", "datasetVersion 不同（base=" + hb.datasetVersion()
                    + ", head=" + hh.datasetVersion() + "），跨版本指标禁止对比；"
                    + "请对 head 版本重新跑一次全量作为新基线");
        }
        Map<String, CaseResult> base = index(store.readCases(baseRun));
        Map<String, CaseResult> head = index(store.readCases(headRun));

        List<Map<String, String>> fixed = new ArrayList<>();
        List<Map<String, String>> newlyFailed = new ArrayList<>();
        List<Map<String, String>> stillFailed = new ArrayList<>();
        List<Map<String, String>> notRerun = new ArrayList<>();

        for (Map.Entry<String, CaseResult> e : base.entrySet()) {
            CaseResult b = e.getValue();
            CaseResult h = head.get(e.getKey());
            if (b.isFail()) {
                if (h == null) {
                    notRerun.add(brief(b));
                } else if (!h.isFail()) {
                    fixed.add(briefTo(h, b));
                } else {
                    stillFailed.add(Map.of("caseId", b.caseId(), "layer", nvl(h.failureLayer()),
                            "question", nvl(h.question())));
                }
            } else if (h != null && h.isFail()) {
                newlyFailed.add(Map.of("caseId", h.caseId(), "layer", nvl(h.failureLayer()),
                        "question", nvl(h.question())));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", baseRun);
        out.put("head", headRun);
        out.put("fixedCount", fixed.size());
        out.put("newlyFailedCount", newlyFailed.size());
        out.put("rollbackHint", newlyFailed.isEmpty()
                ? "无回归，可合入（修完仍需一次全量回归，SOP 第⑤步）"
                : "存在新增失败：本轮修改禁止合入，回滚重查（SOP 第④步）");
        out.put("fixed", fixed);
        out.put("newlyFailed", newlyFailed);
        out.put("stillFailed", stillFailed);
        out.put("notRerun", notRerun);
        return out;
    }

    private Map<String, CaseResult> index(List<CaseResult> cases) {
        Map<String, CaseResult> m = new LinkedHashMap<>();
        cases.forEach(c -> m.put(c.caseId(), c));
        return m;
    }

    private Map<String, String> brief(CaseResult c) {
        return Map.of("caseId", c.caseId(), "verdict", c.verdict(), "question", nvl(c.question()));
    }

    private Map<String, String> briefTo(CaseResult h, CaseResult b) {
        return Map.of("caseId", h.caseId(), "from", b.verdict(), "to", h.verdict(),
                "question", nvl(h.question()));
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
