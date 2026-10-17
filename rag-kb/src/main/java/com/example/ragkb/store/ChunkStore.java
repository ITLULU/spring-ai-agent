package com.example.ragkb.store;

import java.util.Collection;
import java.util.List;

import com.example.ragkb.chunking.Chunk;

/**
 * 检索单元仓库（id → Chunk）的存储抽象。职责：
 *   1) 给 BM25 提供语料；2) 混合检索融合后按 id 回取原文；
 *   3) parent-child 策略下按 id 回取 parent 内容。
 * 两种实现按 {@code rag.chunk-store.type} 切换：
 *   - memory（默认）：进程内 ConcurrentHashMap，重启即空——零基建，clone 即跑；
 *   - postgres：落 Postgres 元数据表，向量库(Milvus)+chunk 库(PG)双持久化，
 *     重启后由启动 warmup 从 PG 重建 BM25——这是 DAT-01"混合检索静默退化"的根治。
 * 为什么接口化而不在具体类上开关：与 TeiReranker/NoopReranker 同一装配范式
 * （@ConditionalOnProperty），业务层只依赖本接口，切换实现零改动。
 */
public interface ChunkStore {

    /** 入库追加（同 id 覆盖）：IngestService 三路写入的第一路，先于向量库/BM25 完成 */
    void putAll(Collection<Chunk> newChunks);

    /** 按 id 回取原文：融合后装配与 parentContent 回取的唯一入口；miss 返回 null 由调用方判空 */
    Chunk get(String id);

    /** 全量快照：供 BM25 重建与 reset 枚举 id 用（返回不可变拷贝） */
    List<Chunk> all();

    /** 批量删除：返回真删掉的块，向量库删除需同一批 id 对齐用 */
    List<Chunk> removeByIds(List<String> ids);

    /**
     * 按 source 删除该文档的全部旧块并返回它们——幂等入库（ING-02）的关键：
     * 重灌同一文档前，先删旧块拿到旧 id，再去向量库删同一批 id，杜绝重复语料。
     */
    List<Chunk> removeBySource(String source);

    /** 当前块数：健康检查/入库日志用，不代表 token 规模 */
    int size();

    /** 存储后端标识（memory|postgres），供 warmup 日志与健康检查展示"数据到底存在哪" */
    String backend();
}
