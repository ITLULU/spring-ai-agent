package com.example.ragkb.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Postgres chunk 库装配：仅当 {@code rag.chunk-store.type=postgres} 时生效。
 * 默认内存模式下不创建 DataSource（application.yml 已排除 DataSourceAutoConfiguration），
 * 保证"零基建 clone 即跑"不被破坏——这是与向量库 Milvus 同款的条件装配思路。
 * <p>用 HikariCP（spring-boot-starter-jdbc 自带）而非每请求新建连接的 DriverManagerDataSource：
 * 无池化时入库/warmup 的每一次写都是完整的 TCP+认证建连，批量场景下成本被 N 倍放大；
 * 小池（默认 5 连接）既消除重建开销，又给 PG 侧 max_connections 留出可换算的预算。
 */
@Configuration
@ConditionalOnProperty(name = "rag.chunk-store.type", havingValue = "postgres")
public class ChunkStoreDataSourceConfig {

    @Bean
    public DataSource chunkDataSource(
            @Value("${rag.postgres.url:jdbc:postgresql://localhost:5432/ragkb}") String url,
            @Value("${rag.postgres.username:postgres}") String username,
            @Value("${rag.postgres.password:postgres}") String password,
            @Value("${rag.postgres.pool-size:5}") int poolSize) {
        // 先建池再建表：建表失败时池正常关闭，不留悬空连接
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        // 池×实例总和必须 < PG max_connections（默认 100），多实例部署前先算这笔账
        ds.setMaximumPoolSize(poolSize);
        ds.setPoolName("chunk-pg");
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
