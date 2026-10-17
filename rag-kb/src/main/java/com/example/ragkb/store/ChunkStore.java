package com.example.ragkb.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.ragkb.chunking.Chunk;

/**
 * 检索单元的内存仓库（id → Chunk）。
 * 职责：1) 给 BM25 提供语料；2) 混合检索融合后按 id 回取原文；
 *      3) parent-child 策略下按 id 回取 parent 内容。
 * 说明：这是脚手架级的内存实现；生产上应落库（如 Postgres + pgvector 元数据表），
 *      并在 README 的"技术决策"里写清这个 trade-off。
 */
@Component
public class ChunkStore {

    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();

    public void putAll(Collection<Chunk> newChunks) {
        newChunks.forEach(c -> chunks.put(c.id(), c));
    }

    public Chunk get(String id) {
        return chunks.get(id);
    }

    public List<Chunk> all() {
        return List.copyOf(chunks.values());
    }

    public List<Chunk> removeByIds(List<String> ids) {
        List<Chunk> removed = ids.stream().map(chunks::remove).filter(java.util.Objects::nonNull).toList();
        return removed;
    }

    public int size() {
        return chunks.size();
    }
}
