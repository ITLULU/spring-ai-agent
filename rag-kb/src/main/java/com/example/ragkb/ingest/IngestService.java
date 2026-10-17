package com.example.ragkb.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ragkb.chunking.Chunk;
import com.example.ragkb.chunking.ChunkingStrategy;
import com.example.ragkb.config.DataPath;
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

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;
    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    public IngestService(DocumentParserService parser, VectorStore vectorStore,
                         ChunkStore chunkStore, Bm25Index bm25Index,
                         List<ChunkingStrategy> chunkingStrategies) {
        this.parser = parser;
        this.vectorStore = vectorStore;
        this.chunkStore = chunkStore;
        this.bm25Index = bm25Index;
        chunkingStrategies.forEach(s -> strategies.put(s.name(), s));
    }

    /**
     * 入库入口：单个文件直接灌；传目录则递归批量入库（ING-01）——
     * 按扩展名白名单过滤（pdf/txt/md），排序保证跨平台遍历顺序稳定，
     * BM25 在全部文件写完后只重建一次（全量重建毫秒级，循环内重建是白费的 O(n²)）。
     */
    public int ingest(String path) throws IOException {
        Path dir = DataPath.resolve(path);
        if (Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> DocumentParserService.isSupported(p.getFileName().toString()))
                        .sorted()
                        .toList();
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("目录下没有支持的文件（pdf/txt/md）: " + dir);
            }
            int total = 0;
            for (Path f : files) {
                total += ingestFile(f.toString());
            }
            bm25Index.rebuildFrom(chunkStore);
            return total;
        }
        int n = ingestFile(path);
        bm25Index.rebuildFrom(chunkStore);
        return n;
    }

    /**
     * 单文件入库（幂等，ING-02）：先按 source 删旧块并取回旧 id → 用同一批 id 删向量库，
     * 再三路写入。配合确定性 chunkId（Chunk.stableId），重灌同一文档不产生重复语料；
     * 切块策略/内容变更导致旧 id 对不上时，先删旧再写新也保证不残留。
     * 注意 BM25 不在此处重建，由 {@link #ingest} 统一收口。
     */
    private int ingestFile(String path) throws IOException {
        String text = parser.parseToText(path);
        String source = path.substring(path.lastIndexOf('/') + 1);
        source = source.substring(source.lastIndexOf('\\') + 1);

        ChunkingStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalStateException("未知切块策略: " + strategyName
                    + "，可选: " + strategies.keySet());
        }

        // 幂等关键：写之前先清该 source 的旧块（ChunkStore + 向量库同一批旧 id）
        List<String> staleIds = chunkStore.removeBySource(source).stream().map(Chunk::id).toList();
        if (!staleIds.isEmpty()) {
            vectorStore.delete(staleIds);
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
        //不再全部重建
//        bm25Index.rebuildFrom(chunkStore);
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
