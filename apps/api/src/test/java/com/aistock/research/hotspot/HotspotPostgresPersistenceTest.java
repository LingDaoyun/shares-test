package com.aistock.research.hotspot;

import com.aistock.research.integration.news.IndustryNewsClient;
import com.aistock.research.integration.news.IndustryNewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HotspotPostgresPersistenceTest {
    @Test
    void upgradesVersionTenAndRestoresOriginalRecommendationWithoutFetching() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "Dedicated PostgreSQL test credentials are required");
        assertThat(url).contains("_test_");
        String schema = "hotspot_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("10")).load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO runtime_config_section (section_key, payload_json, revision, updated_at)
                        VALUES ('existing-hotspot-test', '{"keep":true}', 7, CURRENT_TIMESTAMP)
                        """);
            }
            Flyway latest = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(latest.migrate().migrationsExecuted).isZero();

            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                var mapper = new ObjectMapper().findAndRegisterModules();
                var repository = new HotspotRepository(jdbc, mapper);
                assertThat(repository.load()).isEmpty();
                Instant first = Instant.parse("2026-10-19T01:00:00Z");
                var news = new IndustryNewsItem("source-1", "产品发布", "测试来源", "https://example.com/release",
                        first.minusSeconds(60).toString(), "产品催化与公司关联证据");
                var stock = new HotspotRecommendation("000001", "候选公司", "行业", "推荐理由", "传导机制", "NEWS_MENTION",
                        List.of(news), List.of(), List.of("订单待核验"), "MEDIUM", new BigDecimal("10.25"), new BigDecimal("2.50"),
                        "https://example.com/quote", first.plusSeconds(60).toString(), first, new BigDecimal("10.00"), first.toString());
                var event = new HotspotEvent("event-1", "产品事件", "PRODUCT", List.of("行业"), List.of("产品"), "摘要", "催化",
                        70, "MEDIUM", List.of(news), List.of(stock), List.of("待核验"), first, first.plusSeconds(60));
                var snapshot = new HotspotSnapshot("PARTIAL", first.plusSeconds(60), first, first, first.plusSeconds(7200),
                        first.plusSeconds(900), "持续跟踪", 1, 1, List.of(news), List.of(event), List.of("来源覆盖有限"));
                var saved = new HotspotRepository.State(snapshot, "a".repeat(64));
                repository.save(saved);
                var restored = new HotspotRepository(jdbc, mapper).load().orElseThrow();
                assertThat(restored).isEqualTo(saved);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM industry_hotspot_snapshot", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT revision FROM runtime_config_section WHERE section_key='existing-hotspot-test'", Long.class)).isEqualTo(7);

                var newsClient = mock(IndustryNewsClient.class);
                var analysis = mock(HotspotAnalysisService.class);
                var companies = mock(HotspotCompanyClient.class);
                var restarted = new HotspotService(newsClient, analysis, companies, new HotspotRepository(jdbc, mapper), false, 120);
                try {
                    assertThat(restarted.current()).isEqualTo(snapshot);
                    restarted.checkSchedule();
                    verifyNoInteractions(newsClient, analysis, companies);
                } finally { restarted.shutdown(); }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
