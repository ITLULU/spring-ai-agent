package com.example.ragkb.retrieval;

import java.util.List;

/**
 * Rerank 抽象。生产上二选一：
 *  a) 自部署 TEI/bge-reranker（GPU，见 docker-compose.yml 注释）；
 *  b) 托管 API（硅基流动/GPUStack 等 OpenAI 兼容 /rerank）。
 * 脚手架默认 Noop（开发机无 GPU 也能跑通），接口留在，切换只改配置。
 * 面试点：为什么 Rerank 有效——bi-encoder 独立编码 query 与 doc，cross-encoder
 * 让两者 token 级互相 attend，精度高但无法预计算，所以只做 Top-N 精排。
 */
public interface Reranker {

    /**
     * 精排契约：输入融合后的候选（已按粗略名次排序），输出重新打分的前 topN。
     * <p>实现约束：返回数 ≤ topN（不足全给）；候选必须按相关性降序；
     * 每条命中应通过 withRerank 携带精排分（null 表示未精排，展示为 n/a），
     * 原始三路分数保持不变——评测归因要对比"rerank 前后名次变化"。
     *
     * @param query      原始问句（cross-encoder 需要 query-doc 拼对，不是重新编码 doc）
     * @param candidates 待精排候选（注意可能是 subList 视图，实现方不得修改）
     * @param topN       最终保留数（= rag.retrieval.final-k，喂 LLM 的上下文预算）
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
