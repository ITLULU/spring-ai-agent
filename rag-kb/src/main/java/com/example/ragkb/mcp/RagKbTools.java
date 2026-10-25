package com.example.ragkb.mcp;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.ragkb.eval.DatasetService;
import com.example.ragkb.eval.EvalJudgeErrorLog;
import com.example.ragkb.eval.EvalService;
import com.example.ragkb.eval.QaCase;
import com.example.ragkb.ingest.IngestService;
import com.example.ragkb.qa.QaService;
import com.example.ragkb.qa.QueryResponse;
import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

/**
 * MCP tools 层——"接口不变、实现替换"架构纪律的又一次兑现：
 * 这一层不写任何新业务逻辑，只把 KbController/EvalController 背后已有的 Service 方法s
 * 用 @Tool 注解重新包装成 MCP 协议下的标准工具。REST 与 MCP 是同一能力的两种传输形态。
 *
 * description 是给 LLM 看的"工具选择说明书"——写清何时用/何时不用、返回什么，
 * 这决定了 Agent 编排质量，是 MCP 化改造里真正需要打磨的文案。
 */
@Component
public class RagKbTools {

    private final QaService qaService;
    private final HybridRetrievalService retrievalService;
    private final IngestService ingestService;
    private final EvalService evalService;
    private final DatasetService datasetService;
    private final EvalJudgeErrorLog judgeErrorLog;

    public RagKbTools(QaService qaService, HybridRetrievalService retrievalService,
                      IngestService ingestService, EvalService evalService,
                      DatasetService datasetService, EvalJudgeErrorLog judgeErrorLog) {
        this.qaService = qaService;
        this.retrievalService = retrievalService;
        this.ingestService = ingestService;
        this.evalService = evalService;
        this.datasetService = datasetService;
        this.judgeErrorLog = judgeErrorLog;
    }

    // ===== 知识库能力 =====

    @Tool(name = "kb_query", description = """
            对企业规章制度知识库提问，返回带编号引用（citations）的答案。
            内部执行完整 RAG 链路：混合检索→重排→LLM 生成。超出文档范围的问题会拒答（refused=true）。
            回答政策类问题优先用本工具而不是 kb_retrieve（后者只召回、不调 LLM）。
            追问（含"那/它/这个"等指代词）必须传同一个 conversationId，否则服务端看不到上一轮、无法消解指代。
            """)
    public Map<String, Object> kbQuery(
            @ToolParam(description = "自然语言问题，例如：员工入职满 3 年有多少天年假？") String question,
            @ToolParam(required = false, description = "可选：会话 ID，同一 ID 内支持多轮追问；留空即单轮无状态问答") String conversationId) {
        QueryResponse r = qaService.answer(question, conversationId);
        var m = new LinkedHashMap<String, Object>();
        m.put("answer", r.answer());
        m.put("refused", r.refused());
        m.put("citations", r.citations());
        m.put("elapsedMs", r.elapsedMs());
        m.put("retrievalMs", r.retrievalMs());
        m.put("tokens", Map.of("prompt", r.promptTokens(), "completion", r.completionTokens()));
        return m;
    }

    @Tool(name = "kb_retrieve", description = """
            纯检索调试工具：只执行混合检索（向量+BM25+RRF），不调用 LLM，不消耗生成 token。
            用于归因分析——答案错时先判断是"检索没召回证据"还是"生成出错"。
            可选 evidence 传入期望命中的证据锚文本，逐块返回 evidenceHit（与评测机同一判定口径）。
            """)
    public List<Map<String, Object>> kbRetrieve(
            @ToolParam(description = "检索查询语句") String question,
            @ToolParam(required = false, description = "可选：期望被召回的证据锚文本（题目 JSON 里的 anchorStart）") String evidence) {
        String probe = evidence == null || evidence.isBlank() ? null
                : DatasetService.probeOf(evidence);
        return retrievalService.retrieve(question).stream()
                .map(c -> retrievalRow(c, probe))
                .toList();
    }

    @Tool(name = "kb_ingest", description = """
            把指定路径的文档（单个文件或目录）解析、切块并写入向量库+BM25 索引。
            返回写入的 chunk 数、成功/失败文件数与逐文件错误清单（部分失败不连坐）。
            知识库为空或换了切块策略后需要调用本工具重建。
            """)
    public Map<String, Object> kbIngest(
            @ToolParam(description = "文档或目录路径，例如 data/docs") String path) throws IOException {
        IngestService.IngestResult r = ingestService.ingest(path);
        var m = new LinkedHashMap<String, Object>();
        m.put("chunks", r.chunks());
        m.put("filesOk", r.filesOk());
        m.put("filesFailed", r.filesFailed());
        if (!r.errors().isEmpty()) {
            m.put("errors", r.errors());
        }
        m.put("strategy", ingestService.currentStrategy());
        return m;
    }

    // ===== 评测机能力 =====

    @Tool(name = "eval_dataset_validate", description = """
            跑批前守门校验：逐题检查测试集锚点是否仍能在源文档中定位（文档被改而题没改会检出 STALE）。
            返回 total/stale/datasetVersion。有 stale 时不要启动评测跑批，先修题。
            """)
    public Map<String, Object> evalDatasetValidate() throws IOException {
        List<QaCase> cases = datasetService.loadCases();
        Map<String, DatasetService.ValidationItem> report = datasetService.validate(cases);
        List<String> stale = report.values().stream()
                .filter(v -> !v.ok())
                .map(v -> v.caseId() + ":" + v.reason())
                .toList();
        return Map.of("datasetVersion", datasetService.datasetVersion(),
                "total", cases.size(), "stale", stale.size(), "staleCases", stale);
    }

    @Tool(name = "eval_run_start", description = """
            异步启动一次评测跑批（LLM-as-Judge 全自动判分），立即返回 runId，不阻塞。
            onlyFailedFrom 传入历史 runId 可只重跑其中的错题本（held-out 题自动排除）；
            smoke=true 只抽 20 题主集快速验证改动方向；resumeRunId 传入被中断的 runId 可断点续跑。
            已有 run 在执行时返回错误说明，用 eval_run_status 轮询进度（约每题 5-10 秒）。
            """)
    public Map<String, Object> evalRunStart(
            @ToolParam(description = "本次跑批标签，如 after-rerank-on，用于生成 runId") String tag,
            @ToolParam(required = false, description = "可选：错题本来源 runId，只重跑其中失败的题") String onlyFailedFrom,
            @ToolParam(required = false, description = "可选：true 时执行 smoke 快速模式") Boolean smoke,
            @ToolParam(required = false, description = "可选：断点续跑的目标 runId") String resumeRunId) {
        try {
            String runId = evalService.startRun(tag, onlyFailedFrom,
                    Boolean.TRUE.equals(smoke), resumeRunId);
            return Map.of("runId", runId, "status", "started");
        } catch (IllegalStateException e) {
            return Map.of("status", "rejected", "reason", String.valueOf(e.getMessage()));
        }
    }

    @Tool(name = "eval_run_status", description = """
            查询评测跑批的进度与汇总：running/progress(done/total)/主集与 held-out 分开的
            pass/fail/error 计数、failureLayerCounts 归因分布、totalTokens 成本。
            """)
    public Map<String, Object> evalRunStatus(
            @ToolParam(description = "eval_run_start 返回的 runId") String runId) throws IOException {
        return evalService.status(runId);
    }

    @Tool(name = "eval_run_report", description = """
            获取一次评测跑批的 Markdown 完整报告（含每题 verdict/failureLayer/判词），
            适合直接呈现给人工复核。跑批未完成时报告只含已完成部分。
            """)
    public String evalRunReport(
            @ToolParam(description = "目标 runId") String runId) throws IOException {
        return evalService.report(runId);
    }

    @Tool(name = "eval_run_diff", description = """
            对比两次跑批（单变量实验的裁决器）：返回 fixed/newlyFailed/stillFailed/notRerun。
            两次 run 的 datasetVersion 不一致时拒绝对比（题目变了，胜负无意义）。
            """)
    public Map<String, Object> evalRunDiff(
            @ToolParam(description = "基线 runId") String baseRunId,
            @ToolParam(description = "对比 runId") String headRunId) throws IOException {
        try {
            return evalService.diff(baseRunId, headRunId);
        } catch (IllegalArgumentException e) {
            return Map.of("status", "rejected", "reason", String.valueOf(e.getMessage()));
        }
    }

    @Tool(name = "eval_list_runs", description = """
            列出所有历史评测 run 的 runId（按时间排序），用于挑选 diff 基线和错题本来源。
            """)
    public List<String> evalListRuns() throws IOException {
        return evalService.listRuns();
    }

    // ===== HITL（人在回路）沉淀 =====

    @Tool(name = "judge_error_record", description = """
            人工复核专用：当人类判定 LLM Judge 的 verdict 是误判时，把这条分歧记入 judge-errors.md 清单。
            仅供人在回路场景调用——Agent 不应自行判断 Judge 错了并调用本工具（那会把循环变成自说自话）。
            清单攒到约 10 条，将典型误判作为反例追加进 Judge prompt 并跑 smoke 验证。
            """)
    public Map<String, Object> judgeErrorRecord(
            @ToolParam(description = "误判发生的 runId") String runId,
            @ToolParam(description = "题目 caseId，如 att-003") String caseId,
            @ToolParam(description = "Judge 的判定，如 INCORRECT") String judgeVerdict,
            @ToolParam(description = "人工复核后的判定，如 CORRECT") String humanVerdict,
            @ToolParam(required = false, description = "备注：误判原因分析") String note) throws IOException {
        String savedTo = judgeErrorLog.append(new EvalJudgeErrorLog.JudgeErrorEntry(
                runId, caseId, judgeVerdict, humanVerdict, note));
        return Map.of("savedTo", savedTo, "caseId", caseId);
    }

    /** 与 KbController /api/retrieve 同一渲染口径，避免两套现场快照 */
    private static Map<String, Object> retrievalRow(RetrievedChunk c, String probe) {
        var m = new LinkedHashMap<String, Object>();
        m.put("chunkId", c.chunkId());
        m.put("source", c.source());
        m.put("vectorScore", c.vectorScore());
        m.put("bm25Score", c.bm25Score());
        m.put("fusedScore", c.fusedScore());
        if (probe != null) {
            m.put("evidenceHit", DatasetService.normalize(c.generationContent()).contains(probe)
                    || DatasetService.normalize(c.content()).contains(probe));
        }
        m.put("preview", c.content().substring(0, Math.min(80, c.content().length())));
        return m;
    }
}
