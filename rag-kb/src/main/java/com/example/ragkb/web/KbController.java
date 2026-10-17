package com.example.ragkb.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ragkb.eval.DatasetService;
import com.example.ragkb.ingest.IngestService;
import com.example.ragkb.qa.QaService;
import com.example.ragkb.qa.QueryResponse;
import com.example.ragkb.retrieval.HybridRetrievalService;

/**
 * 演示/评测用 API。
 * /api/retrieve 特意暴露"纯检索不调 LLM"的端点：归因分析专用——
 * 检索错 → 调切块/混合检索/rerank；检索对但答案错 → 调提示词/上下文组织。
 * 这个"先归因再优化"的方法论就是面试系统设计题的答案骨架。
 */
@RestController
@RequestMapping("/api")
public class KbController {

    private final IngestService ingestService;
    private final HybridRetrievalService retrievalService;
    private final QaService qaService;

    public KbController(IngestService ingestService, HybridRetrievalService retrievalService,
                        QaService qaService) {
        this.ingestService = ingestService;
        this.retrievalService = retrievalService;
        this.qaService = qaService;
    }

    public record IngestRequest(String path) {
    }

    public record QueryRequest(String question) {
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest req) throws Exception {
        int n = ingestService.ingest(req.path());
        return Map.of("chunks", n, "strategy", ingestService.currentStrategy());
    }

    @PostMapping("/ingest/reset")
    public Map<String, Object> reset() {
        ingestService.reset();
        return Map.of("status", "cleared");
    }

    /**
     * 纯检索调试端点（不消耗生成 token）。
     * evidence 参数（可选）：传入错题的证据锚文本，逐块返回 evidenceHit——
     * 人工排查 RETRIEVAL_MISS 时一条 curl 看全现场（《方案》§4.1 实现要点），
     * 命中判定与评测机用同一个 DatasetService.probeOf 口径，不会出现两套答案。
     */
    @PostMapping("/retrieve")
    public List<Map<String, Object>> retrieve(@RequestBody QueryRequest req,
                                              @RequestParam(required = false) String evidence) {
        String probe = evidence == null || evidence.isBlank() ? null
                : DatasetService.probeOf(evidence);
        return retrievalService.retrieve(req.question()).stream()
                .map(c -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("chunkId", c.chunkId());
                    m.put("source", c.source());
                    m.put("vectorScore", c.vectorScore());
                    m.put("bm25Score", c.bm25Score());
                    m.put("fusedScore", c.fusedScore());
                    m.put("rerankScore", c.rerankScore() == null ? "n/a" : c.rerankScore());
                    if (probe != null) {
                        m.put("evidenceHit", DatasetService.normalize(c.generationContent()).contains(probe)
                                || DatasetService.normalize(c.content()).contains(probe));
                    }
                    m.put("preview", c.content().substring(0, Math.min(80, c.content().length())));
                    return (Map<String, Object>) m;
                })
                .toList();
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest req) {
        return qaService.answer(req.question());
    }
}
