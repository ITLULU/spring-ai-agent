package com.example.ragkb.retrieval;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未启用 rerank 时直通（默认）。 */
@Component
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "false", matchIfMissing = true)
public class NoopReranker implements Reranker {

    /**
     * 直通实现：不重新打分，只按输入顺序（=RRF 融合名次）截前 topN。
     * rerankScore 保持 null，调试端点展示为 n/a——评测归因时一眼能看出这轮实验
     * 没开精排，不会把融合名次误读成精排名次。
     * 注意 subList 返回的是视图不是拷贝：调用方只做读操作，安全但不可依赖其可变性。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }
}
