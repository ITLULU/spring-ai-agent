package com.example.ragkb.chunking;

import java.util.Map;

/**
 * 切块后的最小存储单元。
 * id 全局唯一（UUID），同时作为向量库与 BM25 索引的关联键，混合检索靠它做融合去重。
 * metadata 会透传给向量库（Spring AI Document.metadata），parent-child 策略用其中
 * 的 parentId / parentContent 实现"小块检索、大块生成"。
 */
public record Chunk(
        String id,
        String content,
        String source,
        int index,
        Map<String, Object> metadata) {
}
