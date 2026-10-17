package com.example.ragkb.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Postgres chunk 库装配：仅当 {@code rag.chunk-store.type=postgres} 时生效。
 * 默认内存模式下不创建 DataSource（application.yml 已排除 DataSourceAutoConfiguration），
 * 保证"零基建 clone 即跑"不被破坏——这是与向量库 Milvus 同款的条件装配思路。
 * <p>这里用 DriverManagerDataSource（每请求新建连接、无池化）而非 HikariCP：本项目的 PG
 * 读写只发生在入库/warmup，非高频在线路径，够用且少一个依赖；真要上线高并发时换成
 * 池化 DataSource 是机械工作（面试可讲的 trade-off）。
 */
@Configuration
@ConditionalOnProperty(name = "rag.chunk-store.type", havingValue = "postgres")
public class ChunkStoreDataSourceConfig {

    @Bean
    public DataSource chunkDataSource(
            @Value("${rag.postgres.url:jdbc:postgresql://localhost:5432/ragkb}") String url,
            @Value("${rag.postgres.username:rag}") String username,
            @Value("${rag.postgres.password:rag}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        initSchema(ds);
        return ds;
    }

    /** 启动即建表（幂等 IF NOT EXISTS）：省去外部 migration 工具，落库演进第一步 */
    private void initSchema(DataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunk (
                    id       VARCHAR(64) PRIMARY KEY,
                    source   TEXT        NOT NULL,
                    idx      INT         NOT NULL,
                    content  TEXT        NOT NULL,
                    metadata TEXT        NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_source ON chunk (source)");
    }
}
