package com.example.ragkb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TeiReranker 响应解析契约测试（2026-10-18 台式机 V4 验证发现：本地 TEI 1.9 实际返回
 * 裸数组 [{"index,score}]，旧实现只认托管 API 的 {"results":[{relevance_score}]} 形态，
 * GPU 精排从未生效且全部静默降级——本测试把两种真实形态的契约钉死，防再分叉）。
 */
class TeiRerankerTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 造三个候选块，内容无关，只用 index/score 验证映射 */
    private static List<RetrievedChunk> candidates() {
        return List.of(
                new RetrievedChunk("c0", "t0", "t0", "s0.md", 0.9, 1.0, 0.5, null),
                new RetrievedChunk("c1", "t1", "t1", "s1.md", 0.8, 2.0, 0.4, null),
                new RetrievedChunk("c2", "t2", "t2", "s2.md", 0.7, 3.0, 0.3, null));
    }

    @Test
    void teiNativeBareArray_isParsedWithScoreField() throws Exception {
        // TEI 原生形态：裸数组 + 字段名 score，且故意乱序返回（index 必须回映射到正确 chunk）
        JsonNode resp = M.readTree("""
                [{"index":2,"score":0.91},{"index":0,"score":0.12},{"index":1,"score":0.55}]
                """);
        List<RetrievedChunk> out = TeiReranker.parseAndApply(resp, candidates(), 3);
        assertEquals(3, out.size());
        assertEquals("c2", out.get(0).chunkId());  // 分数最高排第一
        assertEquals(0.91, out.get(0).rerankScore(), 1e-9);
        assertEquals("c1", out.get(1).chunkId());
        assertEquals("c0", out.get(2).chunkId());
    }

    @Test
    void hostedApiResultsObject_isParsedWithRelevanceScore() throws Exception {
        // 托管 API 形态：{"results":[...]} + 字段名 relevance_score
        JsonNode resp = M.readTree("""
                {"results":[{"index":1,"relevance_score":0.99},{"index":0,"relevance_score":0.01}]}
                """);
        List<RetrievedChunk> out = TeiReranker.parseAndApply(resp, candidates(), 3);
        assertEquals(2, out.size());
        assertEquals("c1", out.get(0).chunkId());
        assertEquals(0.99, out.get(0).rerankScore(), 1e-9);
    }

    @Test
    void bogusIndex_isSkipped_notCrash() throws Exception {
        // 外部响应不可盲信：野 index 只丢该条，合法条目照常映射
        JsonNode resp = M.readTree("""
                [{"index":99,"score":1.0},{"index":-1,"score":2.0},{"index":0,"score":0.5}]
                """);
        List<RetrievedChunk> out = TeiReranker.parseAndApply(resp, candidates(), 3);
        assertEquals(1, out.size());
        assertEquals("c0", out.get(0).chunkId());
    }

    @Test
    void unknownShapeOrAllBogus_returnsEmptyForCallerFallback() throws Exception {
        assertTrue(TeiReranker.parseAndApply(M.readTree("{\"foo\":1}"), candidates(), 3).isEmpty());
        assertTrue(TeiReranker.parseAndApply(M.readTree("[{\"index\":7,\"score\":1}]"), candidates(), 3).isEmpty());
        assertTrue(TeiReranker.parseAndApply(null, candidates(), 3).isEmpty());
    }

    @Test
    void topN_truncatesAfterSorting() throws Exception {
        JsonNode resp = M.readTree("""
                [{"index":0,"score":0.1},{"index":1,"score":0.9},{"index":2,"score":0.5}]
                """);
        List<RetrievedChunk> out = TeiReranker.parseAndApply(resp, candidates(), 2);
        assertEquals(2, out.size());
        assertEquals("c1", out.get(0).chunkId());  // 截断发生在降序之后：留下的应是最高分两名
        assertEquals("c2", out.get(1).chunkId());
    }
}
