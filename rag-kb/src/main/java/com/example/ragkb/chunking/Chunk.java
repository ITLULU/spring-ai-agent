package com.example.ragkb.chunking;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 切块后的最小存储单元。
 * id 用确定性生成（见 stableId）：同 source 同内容重复入库 → id 不变，
 * 这是幂等入库（ING-02）的基础——配合 IngestService 的按 source 删旧，
 * 持久化向量库（Milvus/PG）才不会跨重启累积重复语料。
 * id 同时作为向量库与 BM25 索引的关联键，混合检索靠它做融合去重。
 * metadata 会透传给向量库（Spring AI Document.metadata），parent-child 策略用其中
 * 的 parentId / parentContent 实现"小块检索、大块生成"。
 */
public record Chunk(
        String id,
        String content,
        String source,
        int index,
        Map<String, Object> metadata) {

    /**
     * 确定性 id：nameUUIDFromBytes 基于 MD5，跨 JVM/跨重启结果稳定。
     * 把 index 编进指纹：同文档内重复段落也不会碰撞；改内容/改顺序 → id 变，
     * 旧块由 deleteBySource 清理，不会出现新旧混插的脏命中。
     */
    public static String stableId(String source, int index, String content) {
        return UUID.nameUUIDFromBytes((source + "|" + index + "|" + content)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
