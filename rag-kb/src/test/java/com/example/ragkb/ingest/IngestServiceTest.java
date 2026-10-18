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
 * 入库编排单测：覆盖 ING-01（目录批量入库）与 ING-02（幂等重灌），
 * 以及两个历史阻断级缺陷的回归钉：同名文件跨目录碰撞（source 用相对路径）、
 * 单文件失败不连坐整批（逐文件容错 + BM25 照常收口）。
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

        IngestService.IngestResult r = service.ingest(dir.toString());

        assertTrue(r.chunks() > 0);
        assertEquals(0, r.filesFailed());
        assertEquals(r.chunks(), chunkStore.size());
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

        IngestService.IngestResult first = service.ingest(dir.toString());
        IngestService.IngestResult second = service.ingest(dir.toString());

        // ING-02 核心：重灌不翻倍
        assertEquals(first.chunks(), second.chunks());
        assertEquals(first.chunks(), chunkStore.size());
        // 每个旧块都被从向量库删过一次（确定性 id 下 upsert 也兑底，但删除必须发生：
        // 第二次入库按文件各删一批，共 2 次、总数 = first）
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).delete(captor.capture());
        long deletedTotal = captor.getAllValues().stream().mapToLong(List::size).sum();
        assertEquals(first.chunks(), deletedTotal);
    }

    @Test
    void ingest_emptyDirectory_failsFast() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(dir.toString()));
        assertTrue(e.getMessage().contains("没有支持的文件"));
    }

    @Test
    void ingest_sameFileNameInDifferentSubdirs_bothSurvive() throws IOException {
        // 回归钉：旧实现 source 取裸文件名，sub1/a.md 与 sub2/a.md 碰撞，
        // 后一个的 removeBySource 把前一个的 chunk 全删干净
        Files.createDirectories(dir.resolve("sub1"));
        Files.createDirectories(dir.resolve("sub2"));
        Files.writeString(dir.resolve("sub1").resolve("a.md"), "制度文档甲内容。".repeat(50),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("sub2").resolve("a.md"), "制度文档乙内容。".repeat(50),
                StandardCharsets.UTF_8);

        IngestService.IngestResult r = service.ingest(dir.toString());

        assertEquals(2, r.filesOk());
        Set<String> sources = chunkStore.all().stream().map(c -> c.source())
                .collect(Collectors.toSet());
        assertEquals(Set.of("sub1/a.md", "sub2/a.md"), sources,
                "source 应为相对入库根的路径，跨目录同名不碰撞");
    }

    @Test
    void ingest_oneBadFile_othersStillCommitAndBm25Rebuilds() throws IOException {
        // 回归钉：旧实现单文件异常中断整批且跳过 BM25 重建，
        // 留下"向量库已写、BM25 陈旧"的双索引分裂；新契约逐文件容错 + 照常收口
        write("good.md", "制度文档甲。".repeat(50));
        Files.write(dir.resolve("bad.pdf"), new byte[] { 0, 1, 2, 3 });   // 伪 PDF，解析必失败

        IngestService.IngestResult r = service.ingest(dir.toString());

        assertEquals(1, r.filesOk());
        assertEquals(1, r.filesFailed());
        assertTrue(r.errors().containsKey("bad.pdf"));
        assertTrue(r.chunks() > 0, "成功文件的写入不能因失败文件回滚");
        assertFalse(bm25Index.search("制度文档甲", 10).isEmpty(), "有失败文件时 BM25 也要收口重建");
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
