package com.aistock.research.news;

import com.aistock.research.integration.news.FinancialNewsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialNewsPostgresPersistenceTest {
    @Test
    void upgradesVersionElevenAndRestoresMessagesAndAnalysisWithoutNetwork() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "Dedicated PostgreSQL test credentials are required");
        assertThat(url).contains("_test_");
        String schema = "financial_news_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("11")).load().migrate();
            Flyway latest = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                var mapper = new ObjectMapper().findAndRegisterModules();
                var repository = new FinancialNewsRepository(jdbc, mapper);
                assertThat(repository.load()).isEmpty();
                Instant now = Instant.parse("2026-11-05T02:00:00Z");
                var item = FinancialNewsServiceTest.item("id", "OFFICIAL", "发布", "官方", "https://example.gov.cn/item", "2026-11-05");
                var source = new NewsSourceStatus("gov", "官方", "OFFICIAL", "https://example.gov.cn", "READY", 1, now, now, "成功");
                var analysis = new NewsAnalysis("id", item.summary(), List.of(), "推演", List.of(), List.of(), item.url(), now, List.of());
                var snapshot = new FinancialNewsSnapshot("READY", now, now, List.of(item), List.of(source), Map.of("id", analysis), List.of());
                var state = new FinancialNewsRepository.State(snapshot, now, now.minusSeconds(60));
                repository.save(state);
                assertThat(new FinancialNewsRepository(jdbc, mapper).load()).contains(state);
                var client = mock(FinancialNewsClient.class);
                var analyzer = mock(NewsAnalysisService.class);
                var restarted = new FinancialNewsService(client, analyzer, new FinancialNewsRepository(jdbc, mapper),
                        Clock.fixed(now, ZoneOffset.UTC), Runnable::run, false);
                assertThat(restarted.current()).isEqualTo(snapshot);
                assertThat(restarted.analyze("id")).isEqualTo(analysis);
                restarted.checkSchedule();
                verifyNoInteractions(client, analyzer);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
