package com.example.ragkb.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.ragkb.retrieval.Bm25Index;
import com.example.ragkb.store.ChunkStore;

/**
 * 启动 warmup（DAT-01 的根治）：BM25 是进程内存索引，重启即空，
 * 而向量库（Milvus）是持久的——不处理的话重启后混合检索静默退化成纯向量，
 * 评测分数莫名下滑还查不到原因。
 * <p>做法：应用就绪后立刻从 ChunkStore 全量重建 BM25。
 * chunk-store=memory 时重启两边都空，等价于 no-op；
 * chunk-store=postgres 时语料从 PG 找回，重启后混合检索能力无损恢复。
 * <p>用 ApplicationReadyEvent 而非 @PostConstruct：保证所有 Bean（含数据源建表）
 * 就绪后才读库，且启动日志里 warmup 数字排在最后、便于核对。
 */
@Component
public class StoreWarmupRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreWarmupRunner.class);

    private final ChunkStore chunkStore;
    private final Bm25Index bm25Index;

    public StoreWarmupRunner(ChunkStore chunkStore, Bm25Index bm25Index) {
        this.chunkStore = chunkStore;
        this.bm25Index = bm25Index;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        int n = chunkStore.size();
        bm25Index.rebuildFrom(chunkStore);
        log.info("BM25 warmup 完成: backend={}, chunks={}{}", chunkStore.backend(), n,
                (n == 0 ? "（语料为空——调用 /api/ingest 或 kb_ingest 入库后混合检索才可用）"
                        : "（混合检索重启即恢复，无需重新入库）"));
    }
}
