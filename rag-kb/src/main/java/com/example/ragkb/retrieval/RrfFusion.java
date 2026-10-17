package com.example.ragkb.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）：多路检索结果融合的工业标准做法。
 * score(d) = Σ_每路 1 / (k + rank)，k 通常取 60。
 * 为什么用名次而不是原始分数：向量余弦相似度 [0,1] 和 BM25 分数 [0,+∞) 量纲不同，
 * 直接加权需要先归一化且对分布敏感；RRF 只用名次，天然免调参、鲁棒。
 * （面试高频：为什么混合检索用 RRF 而不是分数加权 → 就是这个回答。）
 */
public final class RrfFusion {

    /**
     * RRF 平滑常数：控制"头部名次的边际优势"。k=60 是经验标准值：
     * rank0 得 1/60≈0.0167，rank9 得 1/69≈0.0145——名次差距被压平，
     * 避免单路第 1 名碾压另一路第 2~5 名；k 越小越偏向各路榜首。
     */
    private static final double K = 60;

    private RrfFusion() {
    }

    /**
     * 融合任意多路召回结果（可变参：目前两路，未来加第三路如元数据过滤路零改动）。
     * <p>算法：逐路扫描，rank 从 0 起，每个 docId 累加 1/(K+rank+1)；
     * 同一块在多路的贡献相加——这就是"两路都前排"比"单路榜首"更强的数学表达。
     * <p>实现细节：
     * <ul>
     *   <li>LinkedHashMap.merge 累加：重复 id 不覆盖而求和，保留首次插入顺序无关紧要，
     *       最后统一重排；</li>
     *   <li>返回按融合分降序的 LinkedHashMap：调用方（HybridRetrievalService）
     *       依赖迭代顺序即名次顺序，不用自己再排。</li>
     * </ul>
     * 复杂度 O(总召回数)，与语料规模无关。
     *
     * @param rankedIdLists 每路的 docId 列表，要路内已按相关度排好（只信名次不信分）
     * @return docId → RRF 融合分，降序
     */
    public static Map<String, Double> fuse(List<String> ... rankedIdLists) {
        Map<String, Double> fused = new LinkedHashMap<>();
        for (List<String> ranked : rankedIdLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                fused.merge(ranked.get(rank), 1.0 / (K + rank + 1), Double::sum);
            }
        }
        // 按融合分降序：保序返回，下游直接按迭代顺序当名次用
        List<Map.Entry<String, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Double> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
