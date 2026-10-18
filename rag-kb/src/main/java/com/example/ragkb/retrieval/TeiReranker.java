package com.example.ragkb.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * TEI（Text Embeddings Inference）/rerank 协议客户端，也兼容主流托管 rerank API：
 * POST {base-url}  body: {"query": "...", "texts": ["..."]}
 * <p>响应存在两种真实形态（2026-10-18 台式机 V4 验证实抓：本地 TEI 1.9 与托管 API 契约不同，
 * 旧实现只认后者，导致 GPU 精排从未生效、全部静默降级）：
 * <ul>
 *   <li>TEI 原生：{@code [{"index":0,"score":0.104}, ...]} —— 裸数组，字段名 score；</li>
 *   <li>托管 API（Cohere/Jina/DashScope 类）：{@code {"results": [{"index":0,"relevance_score":0.98}]}}。</li>
 * </ul>
 * 解析层两种都吃：顶层先看 isArray，否则取 results；分数先取 relevance_score，缺失再取 score。
 */
@Component
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true")
public class TeiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(TeiReranker.class);

    private final RestClient restClient;
    private final String baseUrl;

    public TeiReranker(@org.springframework.beans.factory.annotation.Value("${rag.rerank.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        // 显式超时：无超时的外部调用在依赖挂死时会耗尽 Tomcat 线程池，把整服务拖垮
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * cross-encoder 精排：把 (query, 每块文本) 成对送 rerank 服务，token 级 attend 重新打分。
     * <p>流程与三个关键设计：
     * <ul>
     *   <li>送检文本用 content（子块/原块）而非 generationContent（父块全文）：
     *       cross-encoder 有输入长度预算，精排要的是"命中片段是否真回答得了问题"，
     *       父块多余内容只会稀释信号；</li>
     *   <li>响应用 index 回映射候选位置：rerank 服务可能任意乱序返回，
     *       分数必须贴回正确的 chunk；贴分后用 withRerank 生成新 record（不可变对象模式），
     *       原 candidates 不受污染；</li>
     *   <li>失败降级而非报错：响应缺失/形态不认/<b>连接拒绝/超时/协议异常</b>时退回按融合名次截断，
     *       并 WARN 留痕（降级可见才能被监控发现）——
     *       精排是锦上添花，不该因外部服务抖动把整个 /api/query 打成 500。</li>
     * </ul>
     * <p>成本提示（面试可讲）：每次调用 = topN 次 forward pass，所以只对召回池精排
     * 不对全库精排；这也是"先 RRF 粗筛再 rerank 精排"的两级漏斗存在的原因。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        List<String> texts = candidates.stream().map(RetrievedChunk::content).toList();
        JsonNode resp;
        try {
            resp = restClient.post()
                    .uri(baseUrl)
                    .header("Content-Type", "application/json")
                    .body(Map.of("query", query, "texts", texts))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            // 降级必须覆盖真实故障三态：连接拒绝/读超时/非 2xx，缺一态都是裸奔
            log.warn("rerank 服务不可用，降级为融合名次截断: {} ({})", baseUrl, e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        List<RetrievedChunk> reranked = parseAndApply(resp, candidates, topN);
        if (reranked.isEmpty()) {
            // 覆盖两类无效：结构不认识（无裸数组也无 results）、或全部野 index 被丢
            log.warn("rerank 响应无可用结果（期望 TEI 裸数组或含 results 字段的对象），降级为融合名次截断: {}", baseUrl);
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
        return reranked;
    }

    /**
     * 解析两种响应形态并按 index 回映射打分（包可见静态方法：纯函数，单测直接验契约）。
     * 返回已按 rerankScore 降序截断的列表；响应形态不认或无有效条目时返回空表由调用方降级。
     */
    static List<RetrievedChunk> parseAndApply(JsonNode resp, List<RetrievedChunk> candidates, int topN) {
        if (resp == null) {
            return List.of();
        }
        JsonNode results = resp.isArray() ? resp : resp.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<RetrievedChunk> reranked = new ArrayList<>();
        for (JsonNode r : results) {
            int idx = r.path("index").asInt(-1);
            // 字段名双兼容：TEI 用 score，托管 API 用 relevance_score
            double score = r.has("relevance_score") ? r.path("relevance_score").asDouble(0.0)
                                                    : r.path("score").asDouble(0.0);
            // 外部响应不可盲信：野 index 越界会抛 IndexOutOfBounds 打挂整次查询
            if (idx < 0 || idx >= candidates.size()) {
                log.warn("rerank 返回非法 index={}（候选数 {}），跳过该条", idx, candidates.size());
                continue;
            }
            reranked.add(candidates.get(idx).withRerank(score));
        }
        reranked.sort(Comparator.comparingDouble(RetrievedChunk::rerankScore).reversed());
        return reranked.subList(0, Math.min(topN, reranked.size()));
    }
}
