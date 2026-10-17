package com.example.ragkb.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;

import com.example.ragkb.chunking.FixedSizeChunker;
import com.example.ragkb.retrieval.Bm25Index;
import com.example.ragkb.store.ChunkStore;
import com.example.ragkb.store.InMemoryChunkStore;

/**
 * 入库编排单测：覆盖 ING-01（目录批量入库）与 ING-02（幂等重灌）。
 * 向量库用 Mockito 替身——这里验证的是"编排契约"（删旧 id 与写新块配对、目录过滤），
 * 不是向量库行为本身；ChunkStore/BM25/切块器用真实现，走真实三路写入。
 */
class IngestServiceTest {

    @TempDir
    Path dir;

    private VectorStore vectorStore;
    private ChunkStore chunkStore;
    private Bm25Index bm25Index;
    private IngestService service;

    @BeforeEach
    void setUp() throws Exception {
        vectorStore = mock(VectorStore.class);
        chunkStore = new InMemoryChunkStore();
        bm25Index = new Bm25Index();
        FixedSizeChunker chunker = new FixedSizeChunker();
        set(chunker, "size", 100);
        set(chunker, "overlap", 20);
        service = new IngestService(new DocumentParserService(), vectorStore, chunkStore, bm25Index,
                List.of(chunker));
        set(service, "strategyName", "fixed");
    }

    @Test
    void ingest_directory_bulkLoadsSupportedFilesAndIgnoresOthers() throws IOException {
        write("a.md", "制度文档甲。".repeat(50));
        write("b.txt", "制度文档乙。".repeat(50));
        Files.write(dir.resolve("ignore.png"), new byte[] { 1, 2, 3 });

        int total = service.ingest(dir.toString());

        assertTrue(total > 0);
        assertEquals(total, chunkStore.size());
        Set<String> sources = chunkStore.all().stream().map(c -> c.source())
                .collect(Collectors.toSet());
        assertEquals(Set.of("a.md", "b.txt"), sources);
        // BM25 收口重建：语料全量可查（词法路在目录场景只重建一次）
        assertFalse(bm25Index.search("制度文档甲", 10).isEmpty());
    }

    @Test
    void ingest_sameDirectoryTwice_isIdempotentAndCleansStaleVectors() throws IOException {
        write("a.md", "制度文档甲。".repeat(50));
        write("b.txt", "制度文档乙。".repeat(50));

        int first = service.ingest(dir.toString());
        int second = service.ingest(dir.toString());

        // ING-02 核心：重灌不翻倍
        assertEquals(first, second);
        assertEquals(first, chunkStore.size());
        // 每个旧块都被从向量库删过一次（确定性 id 下 upsert 也兑底，但删除必须发生：
        // 第二次入库按文件各删一批，共 2 次、总数 = first）
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).delete(captor.capture());
        long deletedTotal = captor.getAllValues().stream().mapToLong(List::size).sum();
        assertEquals(first, deletedTotal);
    }

    @Test
    void ingest_emptyDirectory_failsFast() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(dir.toString()));
        assertTrue(e.getMessage().contains("没有支持的文件"));
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
