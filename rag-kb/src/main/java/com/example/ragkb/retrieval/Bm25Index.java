package com.example.ragkb.retrieval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragkb.chunking.Chunk;
import com.example.ragkb.store.ChunkStore;

/**
 * 手写 BM25 倒排索引（Okapi BM25 标准公式，k1=1.5, b=0.75）。
 * 为什么自己写而不上 Lucene：学习项目要"每一行能解释"；且这里规模（千级 chunk）
 * 手写完全够用。生产对标 Lucene/Elasticsearch 的 BM25 实现，公式一致。
 *
 * score(q,d) = Σ_t idf(t) · tf·(k1+1) / (tf + k1·(1-b+b·|d|/avgdl))
 * idf(t)     = ln(1 + (N - df + 0.5) / (df + 0.5))
 *
 * 面试点：BM25 补的是向量检索的短板——精确术语、编号、专有名词（词法匹配），
 * 向量补的是一词多义/同义改写（语义匹配），两者失败模式互补，所以要混合。
 */
@Component
public class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** term → (docId → 词频) */
    private volatile Map<String, Map<String, Integer>> postings = new HashMap<>();
    /** docId → 文档长度（token 数） */
    private volatile Map<String, Integer> docLengths = new HashMap<>();
    private volatile double avgDocLength = 0;
    private volatile int docCount = 0;

    public synchronized void rebuild(Collection<Chunk> corpus) {
        Map<String, Map<String, Integer>> newPostings = new HashMap<>();
        Map<String, Integer> newLengths = new HashMap<>();
        long totalLength = 0;

        for (Chunk chunk : corpus) {
            List<String> tokens = TextTokenizer.tokenize(chunk.content());
            Map<String, Integer> termFreq = new HashMap<>();
            for (String t : tokens) {
                termFreq.merge(t, 1, Integer::sum);
            }
            termFreq.forEach((term, tf) ->
                    newPostings.computeIfAbsent(term, k -> new HashMap<>()).put(chunk.id(), tf));
            newLengths.put(chunk.id(), tokens.size());
            totalLength += tokens.size();
        }

        this.postings = newPostings;
        this.docLengths = newLengths;
        this.docCount = newLengths.size();
        this.avgDocLength = docCount == 0 ? 0 : (double) totalLength / docCount;
    }

    public List<Hit> search(String query, int topN) {
        List<String> queryTokens = TextTokenizer.tokenize(query);
        Map<String, Double> scores = new HashMap<>();

        for (String term : queryTokens) {
            Map<String, Integer> posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            double idf = Math.log(1 + (docCount - posting.size() + 0.5) / (posting.size() + 0.5));
            posting.forEach((docId, tf) -> {
                double dl = docLengths.getOrDefault(docId, 1);
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * dl / Math.max(avgDocLength, 1)));
                scores.merge(docId, idf * norm, Double::sum);
            });
        }

        List<Hit> hits = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topN)
                .forEach(e -> hits.add(new Hit(e.getKey(), e.getValue())));
        return hits;
    }

    /** 从当前 ChunkStore 全量重建索引（数据量小，重建即一致性保证） */
    public void rebuildFrom(ChunkStore store) {
        rebuild(store.all());
    }

    public record Hit(String chunkId, double score) {
    }
}
