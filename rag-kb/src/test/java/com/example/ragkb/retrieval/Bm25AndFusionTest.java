package com.example.ragkb.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ragkb.chunking.Chunk;

class Bm25AndFusionTest {

    @Test
    void bm25_ranksDocContainingQueryTermsHigher() {
        Bm25Index index = new Bm25Index();
        List<Chunk> corpus = List.of(
                new Chunk("a", "员工年假每年 十五天 需提前申请", "d1.md", 0, java.util.Map.of()),
                new Chunk("b", "出差住宿一线城市每晚上限六百元", "d2.md", 0, java.util.Map.of()),
                new Chunk("c", "年假 申请流程与审批权限", "d3.md", 0, java.util.Map.of()));
        index.rebuild(corpus);

        List<Bm25Index.Hit> hits = index.search("年假 申请", 3);
        assertFalse(hits.isEmpty());
        // a 和 c 命中"年假"，b 不含相关词，a/c 应排在 b 前
        assertTrue(hits.stream().limit(2).noneMatch(h -> h.chunkId().equals("b")));
    }

    @Test
    void rrf_prefersDocumentsRankedHighInBothLists() {
        List<String> vector = List.of("x", "y", "z");
        List<String> bm25 = List.of("y", "z", "w");

        var fused = RrfFusion.fuse(vector, bm25);
        // y 在两路都靠前，融合后应排第一
        assertEquals("y", fused.keySet().iterator().next());
    }

    @Test
    void tokenizer_handlesCjkBigramsAndLatinWords() {
        var tokens = TextTokenizer.tokenize("RAG知识库 test");
        assertTrue(tokens.contains("rag"));
        assertTrue(tokens.contains("test"));
        assertTrue(tokens.contains("知识")); // CJK bigram
        assertTrue(tokens.contains("识库"));
    }
}
