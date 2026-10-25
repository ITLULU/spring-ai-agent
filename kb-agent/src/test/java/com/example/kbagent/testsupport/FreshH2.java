package com.example.kbagent.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 每个测试一套独立 H2 内存库，并真的跑一遍 main/resources/schema.sql。
 * 不用 mock 存储层：这层要验的就是 SQL 与 schema 对得上、CAS 条件真的生效。
 */
public final class FreshH2 {

    private FreshH2() {
    }

    public static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1", "sa", "");
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        DatabasePopulatorUtils.execute(populator, ds);
        return ds;
    }
}
