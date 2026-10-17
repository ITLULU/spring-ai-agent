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

    /** 入库追加（同 id 覆盖）：IngestService 三路写入的第一路，先于向量库/BM25 完成 */
    public void putAll(Collection<Chunk> newChunks) {
        newChunks.forEach(c -> chunks.put(c.id(), c));
    }

    /** 按 id 回取原文：融合后装配与 parentContent 回取的唯一入口；miss 返回 null 由调用方判空 */
    public Chunk get(String id) {
        return chunks.get(id);
    }

    /**
     * 全量快照（List.copyOf 不可变拷贝）：供 BM25 重建与 reset 枚举 id 用。
     * copyOf 避免调用方边遍历边写入的并发问题，也封死了外部篡改内部 map 的口子。
     */
    public List<Chunk> all() {
        return List.copyOf(chunks.values());
    }

    /** 批量删除（reset 链路）：返回真删掉的块，向量库删除需同一批 id 对齐用 */
    public List<Chunk> removeByIds(List<String> ids) {
        List<Chunk> removed = ids.stream().map(chunks::remove).filter(java.util.Objects::nonNull).toList();
        return removed;
    }

    /** 当前块数：健康检查/入库日志用，不代表 token 规模 */
    public int size() {
        return chunks.size();
    }
}
