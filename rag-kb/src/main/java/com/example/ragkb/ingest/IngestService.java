package com.example.ragkb.ingest;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ragkb.chunking.Chunk;
import com.example.ragkb.chunking.ChunkingStrategy;
import com.example.ragkb.retrieval.Bm25Index;
import com.example.ragkb.store.ChunkStore;

/**
 * 入库编排：解析 → 切块（策略可切换）→ 三路写入（ChunkStore / 向量库 / BM25 索引）。
 * rag.chunk.strategy 改一个值即可切换切块策略，评测对比就是为这种"单变量实验"设计的。
 */
@Service
public class IngestService {

    private final DocumentParserService parser;
    private final VectorStore vectorStore;
    private final ChunkStore chunkStore;
    private final Bm25Index bm25Index;
    private final Map<String, ChunkingStrategy> strategies = new HashMap<>();

    @Value("${rag.chunk.strategy:recursive}")
    private String strategyName;

    public IngestService(DocumentParserService parser, VectorStore vectorStore,
                         ChunkStore chunkStore, Bm25Index bm25Index,
                         List<ChunkingStrategy> chunkingStrategies) {
        this.parser = parser;
        this.vectorStore = vectorStore;
        this.chunkStore = chunkStore;
        this.bm25Index = bm25Index;
        chunkingStrategies.forEach(s -> strategies.put(s.name(), s));
    }

    public int ingest(String path) throws IOException {
        String text = parser.parseToText(path);
        String source = path.substring(path.lastIndexOf('/') + 1);
        source = source.substring(source.lastIndexOf('\\') + 1);

        ChunkingStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalStateException("未知切块策略: " + strategyName
                    + "，可选: " + strategies.keySet());
        }
        List<Chunk> chunks = strategy.split(source, text);

        chunkStore.putAll(chunks);
        // 只有 parentId 进向量库 metadata；parentContent 留在 ChunkStore，避免向量库膨胀
        vectorStore.add(chunks.stream().map(c -> Document.builder()
                .id(c.id())
                .text(c.content())
                .metadata(Map.of("source", c.source(),
                        "chunkIndex", c.index(),
                        "strategy", strategy.name(),
                        "parentId", c.metadata().getOrDefault("parentId", "")))
                .build()).toList());
        bm25Index.rebuildFrom(chunkStore);
        return chunks.size();
    }

    /** 重新切块实验前清空语料 */
    public void reset() {
        List<String> ids = chunkStore.all().stream().map(Chunk::id).toList();
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
            chunkStore.removeByIds(ids);
            bm25Index.rebuildFrom(chunkStore);
        }
    }

    public String currentStrategy() {
        return strategyName;
    }
}
