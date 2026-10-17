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

    private static final double K = 60;

    private RrfFusion() {
    }

    public static Map<String, Double> fuse(List<String> ... rankedIdLists) {
        Map<String, Double> fused = new LinkedHashMap<>();
        for (List<String> ranked : rankedIdLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                fused.merge(ranked.get(rank), 1.0 / (K + rank + 1), Double::sum);
            }
        }
        // 按融合分降序
        List<Map.Entry<String, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Double> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
