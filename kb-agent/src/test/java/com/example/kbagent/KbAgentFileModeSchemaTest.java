package com.example.kbagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * file 模式建表守门测试。
 *
 * 为什么单独一类：默认配置用的是 `jdbc:h2:file:./data/...`，而 `spring.sql.init.mode`
 * 的默认值 `embedded` 只认 `jdbc:h2:mem:` 前缀——file 库会被判定成"外部库"直接跳过
 * schema.sql。实测症状：进程启动完全正常、/api/tickets/state-machine 也打得开，
 * 但 POST 工单 500「Table "TICKET" not found」。内存库跑的那套测试永远看不到这个坑，
 * 所以这里刻意用 file URL 起一次上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.mcp.client.enabled=false",
        "ticket.agent.retry-scheduler-enabled=false"
})
class KbAgentFileModeSchemaTest {

    @TempDir
    static Path dbDir;

    @DynamicPropertySource
    static void fileModeDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dbDir.resolve("tickets") + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schemaIsCreatedOnAFileDatabaseNotOnlyOnInMemory() {
        // 表不存在时 query 会抛 JdbcSQLSyntaxErrorException，这里要求它安静返回 0 行
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class),
                "ticket 表已建且是空库（file 模式下 schema.sql 真的跑了）");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket_note", Integer.class));

        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.indexes WHERE table_name = 'TICKET'",
                String.class);
        assertDoesNotThrow(() -> jdbc.update(
                "INSERT INTO ticket (id, title, description, status, attempts, next_retry_at, created_at, updated_at) "
                        + "VALUES ('t-schema', '门禁', '卡丢了', 'NEW', 0, 0, 1, 1)"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class));
        assertEquals(true, indexes.contains("IDX_TICKET_RETRY"),
                "重试扫描靠 (status, next_retry_at) 索引，积压时不能全表扫");
    }
}
