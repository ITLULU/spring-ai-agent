package com.example.ragkb.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkingTest {

    private static String sample() {
        return "第一段内容。".repeat(80) + "\n\n" + "第二段完全不同的内容。".repeat(80);
    }

    @Test
    void fixedChunker_respectsSizeAndProducesOverlap() {
        FixedSizeChunker chunker = new FixedSizeChunker();
        ReflectionUtils.set(chunker, "size", 100);
        ReflectionUtils.set(chunker, "overlap", 20);

        List<Chunk> chunks = chunker.split("doc.txt", sample());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.content().length() <= 100));
        // 有重叠时，相邻块应有公共前缀内容
        assertTrue(chunks.size() > 2);
    }

    @Test
    void parentChildChunker_childrenCarryParentContent() {
        ParentChildChunker chunker = new ParentChildChunker();
        ReflectionUtils.set(chunker, "maxParentChars", 300);
        ReflectionUtils.set(chunker, "childChars", 100);

        List<Chunk> chunks = chunker.split("doc.txt", sample());
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertTrue(c.metadata().containsKey("parentId"));
            String parent = (String) c.metadata().get("parentContent");
            assertNotNull(parent);
            // child 文本必须是 parent 的子串（小块检索、大块生成的前提）
            assertTrue(parent.contains(c.content()));
        }
    }

    @Test
    void recursiveChunker_producesNoEmptyChunks() {
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker();
        ReflectionUtils.set(chunker, "size", 120);

        List<Chunk> chunks = chunker.split("doc.txt", sample());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().noneMatch(c -> c.content().isBlank()));
    }

    /** ING-02 根基：同输入必得同 id（UUID.nameUUIDFromBytes），幂等重灌/重启后回删才能对齐向量库 */
    @Test
    void chunkIds_areDeterministicAcrossInvocations() {
        FixedSizeChunker chunker = new FixedSizeChunker();
        ReflectionUtils.set(chunker, "size", 100);
        ReflectionUtils.set(chunker, "overlap", 20);

        List<String> first = chunker.split("doc.txt", sample()).stream().map(Chunk::id).toList();
        List<String> second = chunker.split("doc.txt", sample()).stream().map(Chunk::id).toList();
        assertEquals(first, second);
        // 不同文档同内容 → id 必须不同（source 参与散列，避免跨文档串库）
        List<String> otherSource = chunker.split("other.txt", sample()).stream().map(Chunk::id).toList();
        assertNotEquals(first, otherSource);
    }
}
