package com.example.ragkb.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ragkb.chunking.Chunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ChunkStore 的 Postgres 实现（{@code rag.chunk-store.type=postgres}）。
 * 表结构见 {@code ChunkStoreDataSourceConfig}（启动即建表）：id 主键、source 建索引，metadata 以 JSON 文本列存
 * （parentContent 随子块存一份副本——与小内存实现同款取舍，落库演进第一步不做 parent 表 + 外键）。
 * <p>为什么用 JdbcTemplate 而非 Spring AI 的 vector store：这里存的是"检索单元原文+父子关系"，
 * 是关系型元数据不是向量；向量始终在 Milvus。PG 只补上"重启不丢 BM25 语料/原文回取"这一环。
 * <p>写入用 ON CONFLICT(id) DO UPDATE 幂等 upsert：配合确定性 chunkId，重灌同文档天然去重。
 * putAll 走 batchUpdate + 单事务：千级 chunk 逐条 update 是千次网络往返，且中途失败会留下
 * "半套文档"——批量+事务让一个文档的 chunk 要么全进要么全不进。
 */
@Component
@ConditionalOnProperty(name = "rag.chunk-store.type", havingValue = "postgres")
public class PostgresChunkStore implements ChunkStore {

    /** IN 子句分批上限：万级 id 拼一条 SQL 会撞 PG 参数上限/规划器退化 */
    private static final int IN_BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public PostgresChunkStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void putAll(Collection<Chunk> newChunks) {
        if (newChunks.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO chunk (id, source, idx, content, metadata) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET source = EXCLUDED.source, idx = EXCLUDED.idx, "
                + "content = EXCLUDED.content, metadata = EXCLUDED.metadata";
        List<Object[]> batch = new ArrayList<>();
        for (Chunk c : newChunks) {
            batch.add(new Object[] { c.id(), c.source(), c.index(), c.content(), writeJson(c.metadata()) });
        }
        tx.executeWithoutResult(status -> jdbc.batchUpdate(sql, batch));
    }

    @Override
    public Chunk get(String id) {
        List<Chunk> rows = jdbc.query("SELECT * FROM chunk WHERE id = ?", this::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Chunk> all() {
        return jdbc.query("SELECT * FROM chunk ORDER BY source, idx", this::map);
    }

    @Override
    public List<Chunk> removeByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Chunk> removed = new ArrayList<>();
        // 分批：先取回待删行再删，同事务内完成，避免查询/删除跨批不一致
        tx.executeWithoutResult(status -> {
            for (List<String> part : partition(ids)) {
                String placeholders = String.join(",", part.stream().map(x -> "?").toList());
                removed.addAll(jdbc.query(
                        "SELECT * FROM chunk WHERE id IN (" + placeholders + ")", this::map, part.toArray()));
                jdbc.update("DELETE FROM chunk WHERE id IN (" + placeholders + ")", part.toArray());
            }
        });
        return removed;
    }

    private static List<List<String>> partition(List<String> ids) {
        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_BATCH) {
            parts.add(ids.subList(i, Math.min(i + IN_BATCH, ids.size())));
        }
        return parts;
    }

    @Override
    public List<Chunk> removeBySource(String source) {
        List<Chunk> removed = jdbc.query("SELECT * FROM chunk WHERE source = ?", this::map, source);
        jdbc.update("DELETE FROM chunk WHERE source = ?", source);
        return removed;
    }

    @Override
    public int size() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chunk", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public String backend() {
        return "postgres";
    }

    private Chunk map(ResultSet rs, int rowNum) throws SQLException {
        return new Chunk(
                rs.getString("id"),
                rs.getString("content"),
                rs.getString("source"),
                rs.getInt("idx"),
                readJson(rs.getString("metadata")));
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("chunk metadata 序列化失败", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("chunk metadata 反序列化失败", e);
        }
    }
}
