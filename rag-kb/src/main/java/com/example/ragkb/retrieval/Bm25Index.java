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

    /**
     * 词频饱和参数：tf 对分数的贡献不是线性而是边际递减（tf→∞ 时贡献趋于 k1+1）。
     * 直观理解：一个词出现 1 次和出现 10 次，相关性差很远；但 10 次和 20 次差别不大
     * ——堆关键词的文档骗不过饱和曲线。调大 k1 → 削弱饱和、更接近线性 tf。
     */
    private static final double K1 = 1.5;

    /**
     * 文档长度归一化强度：0 → 完全不管块长（长块天然占优）；1 → 完全按 |d|/avgdl 惩罚。
     * 取 0.75 是经验折中：RAG 场景里切块长度不一（fixed/recursive 策略都会），
     * 不惩罚会让长块挤掉真正命中主题的精短块。
     */
    private static final double B = 0.75;

    /** term → (docId → 词频)。倒排索引主体：检索时只遍历查询词的 posting，与语料规模无关 */
    private volatile Map<String, Map<String, Integer>> postings = new HashMap<>();
    /** docId → 文档长度（token 数），供 |d|/avgdl 长度归一化用 */
    private volatile Map<String, Integer> docLengths = new HashMap<>();
    /** 平均块长 avgdl：公式里的分母基准，重建时重算 */
    private volatile double avgDocLength = 0;
    /** 语料总块数 N：idf 公式的基数，同时也是所有 posting 长度的上限 */
    private volatile int docCount = 0;

    /**
     * 全量重建索引（非增量）：遍历语料 → 分词 → 统计词频 → 构建倒排表。
     * <p>并发设计（面试可讲）：先在新 map 里全部构好，最后一次性替换 volatile 引用——
     * copy-on-write 思路：读线程（{@link #search} 不加锁）要么看到完整旧索引、
     * 要么看到完整新索引，永远不会读到"重建到一半"的脏状态；
     * 写线程之间用 synchronized 互斥，避免两次重建互相覆盖。
     * <p>为什么每次入库都全量重建而不做增量 add？千级 chunk 重建是毫秒级，
     * "重建即一致性"省掉增量删除/更新的补丁逻辑（HashMap 不支持并发增量维护）；
     * 十万级时要改增量或换 Lucene，这是已知的演进边界（见 README TODO）。
     *
     * @param corpus 当前全部切块（来自 ChunkStore，三路写入中的词法路只读 content）
     */
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

    /**
     * 词法检索：对查询分词后逐词查倒排表，按 BM25 公式累加各词贡献，取 Top-N。
     * <p>公式两个组件的意图（对应类注释里的公式）：
     * <ul>
     *   <li>idf：词的区分度。ln(1 + (N-df+0.5)/(df+0.5))，df 越大越接近 0。
     *       加 1 的概率型变体保证恒非负——若用经典形式，df &gt; N/2 的常见词会得负分，
     *       反而惩罚含"制度"这种领域高频词的文档；</li>
     *   <li>tf 饱和 + 长度归一（norm 行）：分子 tf·(k1+1)、分母 tf + k1·(1-b+b·|d|/avgdl)，
     *       同一词出现越多边际收益越小，块越长惩罚越重（由 B 控制强度）。</li>
     * </ul>
     * <p>两个工程细节：
     * <ul>
     *   <li>查询词不在倒排表（posting==null）直接跳过：BM25 是 OR 语义，
     *       未命中词不贡献分数也不淘汰文档（区别于 AND 过滤）；</li>
     *   <li>只对候选集排序（命中的 docId 才进 scores），不是全库排序，
     *       检索成本随查询词覆盖度而非语料量增长。</li>
     * </ul>
     * 注意：分数是无界的相对值（不同 query 间不可比较），所以后续 RRF 融合
     * 只用名次不用原始分——这是向量分与 BM25 分能"混合"的前提。
     *
     * @param query 自然语言问句（内部用 TextTokenizer 做中英混合分词，与入库同一口径，
     *              分词不一致是词法检索第一杀手）
     * @param topN  返回命中数上限（对应 rag.retrieval.top-n，每路召回数）
     * @return 按分数降序的命中列表；空索引/全未命中时返回空表而非报错
     */
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

    /**
     * 从当前 ChunkStore 全量重建索引（数据量小，重建即一致性保证）。
     * 调用时机：每次 ingest/reset 后——词法路、向量路、ChunkStore 三路同源重建，
     * 保证评测归因时 BM25 分数与向量分对应同一份语料快照。
     */
    public void rebuildFrom(ChunkStore store) {
        rebuild(store.all());
    }

    /** 单次检索的一条命中：chunkId 回 ChunkStore 取全文，score 供融合/调试展示 */
    public record Hit(String chunkId, double score) {
    }
}
