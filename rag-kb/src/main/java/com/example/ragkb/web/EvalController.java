package com.example.ragkb.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ragkb.eval.CaseResult;
import com.example.ragkb.eval.DatasetService;
import com.example.ragkb.eval.EvalJudgeErrorLog;
import com.example.ragkb.eval.EvalService;
import com.example.ragkb.eval.QaCase;

/**
 * 评测机 API（《方案》§3.2）。要点：
 *  - /runs 提交即返回 202，评测在后台单线程执行（HTTP 不再被长任务拖死）；
 *  - onlyFailedFrom 实现"错题本二次重点排查"；
 *  - /diff 是回归守门员：newlyFailed 不为空即建议回滚；
 *  - /dataset/validate 是 SOP 第①步：先隔离过期题，再谈系统失败。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final DatasetService datasetService;
    private final EvalJudgeErrorLog judgeErrorLog;

    public EvalController(EvalService evalService, DatasetService datasetService,
                          EvalJudgeErrorLog judgeErrorLog) {
        this.evalService = evalService;
        this.datasetService = datasetService;
        this.judgeErrorLog = judgeErrorLog;
    }

    public record StartRunRequest(String tag, String onlyFailedFrom, Boolean smoke, String resumeRunId) {
    }

    /** 提交评测：全量 / 冒烟 / 错题重跑 / 断点续跑四合一 */
    @PostMapping({"/runs", "/run"})
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) StartRunRequest req) {
        String tag = req == null ? null : req.tag();
        String onlyFailedFrom = req == null ? null : req.onlyFailedFrom();
        boolean smoke = req != null && Boolean.TRUE.equals(req.smoke());
        String resumeRunId = req == null ? null : req.resumeRunId();
        try {
            String runId = evalService.startRun(tag, onlyFailedFrom, smoke, resumeRunId);
            return ResponseEntity.accepted().body(Map.of(
                    "runId", runId,
                    "status", "RUNNING",
                    "progressUrl", "/api/eval/runs/" + runId));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs")
    public List<String> runs() throws Exception {
        return evalService.listRuns();
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Object> status(@PathVariable String runId) {
        try {
            return ResponseEntity.ok(evalService.status(runId));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 错题清单：/runs/{id}/results?verdict=FAIL&layer=RETRIEVAL_MISS */
    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<Object> results(@PathVariable String runId,
                                          @RequestParam(required = false) String verdict,
                                          @RequestParam(required = false) String layer,
                                          @RequestParam(required = false) String caseId) {
        try {
            List<CaseResult> rows = evalService.results(runId, verdict, layer, caseId);
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Markdown 报告由 JSONL 渲染——报告是数据视图不是数据本身 */
    @GetMapping(value = "/runs/{runId}/report", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable String runId) {
        try {
            return ResponseEntity.ok(evalService.report(runId));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 跨 run diff：已修复 / 新增失败 / 仍未修复；datasetVersion 不同时拒绝 */
    @GetMapping("/diff")
    public ResponseEntity<Object> diff(@RequestParam String base, @RequestParam String head) {
        try {
            Map<String, Object> out = evalService.diff(base, head);
            return out.containsKey("error")
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(out)
                    : ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** SOP 第①步：锚点可定位校验，返回每题 ok/stale 明细 */
    @PostMapping("/dataset/validate")
    public Map<String, Object> validateDataset() throws Exception {
        List<QaCase> cases = datasetService.loadCases();
        List<Map<String, Object>> rows = datasetService.validateReport(cases);
        long stale = rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count();
        return Map.of(
                "datasetVersion", datasetService.datasetVersion(),
                "total", rows.size(),
                "stale", stale,
                "hint", stale == 0 ? "全部题目锚点可定位，可开始全量评测"
                        : "存在过期/缺锚点题目，先修复或隔离再跑评测（SOP①）",
                "items", rows);
    }

    public record JudgeErrorRequest(String runId, String caseId, String judgeVerdict,
                                    String humanVerdict, String note) {
    }

    /** 人工复核归档：记录一条 Judge 误判到失误清单（§4.2，轻量校准） */
    @PostMapping("/judge-errors")
    public Map<String, Object> recordJudgeError(@RequestBody JudgeErrorRequest req) throws Exception {
        if (req.caseId() == null || req.caseId().isBlank()
                || req.humanVerdict() == null || req.humanVerdict().isBlank()) {
            return Map.of("error", "caseId 与 humanVerdict 必填");
        }
        String file = judgeErrorLog.append(new EvalJudgeErrorLog.JudgeErrorEntry(
                req.runId(), req.caseId(), req.judgeVerdict(), req.humanVerdict(), req.note()));
        return Map.of("savedTo", file, "hint", "累计约 10 条时将典型误判作为反例追加进 Judge prompt 并跑 smoke");
    }

    @GetMapping("/judge-errors")
    public ResponseEntity<String> judgeErrors() {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
                .body(judgeErrorLog.read());
    }
}
