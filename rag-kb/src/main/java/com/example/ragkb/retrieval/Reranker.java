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

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
