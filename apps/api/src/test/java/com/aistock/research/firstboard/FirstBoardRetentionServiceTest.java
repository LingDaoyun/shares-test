package com.aistock.research.firstboard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FirstBoardRetentionServiceTest {

    private String baseUrl;
    private String username;
    private String password;
    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = System.getenv("AISTOCK_TEST_JDBC_URL");
        username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(baseUrl != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        schema = "first_board_retention_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(baseUrl, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure().dataSource(baseUrl, username, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        String schemaUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, username, password));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema != null) {
            try (var connection = DriverManager.getConnection(baseUrl, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void keepsOnlyBoundedFirstBoardWindowsAndNeverTouchesSharedHistory() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int index = 0; index < 121; index++) {
            LocalDate date = start.plusDays(index);
            jdbc.update("""
                    INSERT INTO first_board_daily_bar (
                      symbol, trade_date, open_price, close_price, high_price, low_price, volume,
                      adjust_mode, source_name, content_hash, observed_at, updated_at
                    ) VALUES ('600000', ?, 10, 10, 10, 10, 100, 'QFQ', 'test', ?, now(), now())
                    """, date, "hash-" + index);
        }
        for (int index = 0; index < 131; index++) {
            LocalDate date = start.plusDays(index);
            jdbc.update("""
                    INSERT INTO first_board_market_day (
                      trade_date, source_trade_date, status, expected_symbol_count, written_symbol_count,
                      failed_symbol_count, limit_up_pool_complete, bar_data_version, started_at, completed_at
                    ) VALUES (?, ?, 'READY', 1, 1, 0, TRUE, ?, now(), now())
                    """, date, date, "bars-" + index);
        }
        for (int index = 0; index < 6; index++) {
            UUID runId = UUID.randomUUID();
            LocalDate scanDate = start.plusDays(index);
            jdbc.update("""
                    INSERT INTO first_board_baseline_run (
                      run_id, scan_trade_date, baseline_trade_date, rule_version, bar_data_version,
                      status, universe_count, supported_count, excluded_name_count,
                      insufficient_history_count, item_count, started_at, published_at
                    ) VALUES (?, ?, ?, 'v1', ?, 'READY', 1, 1, 0, 0, 1, now(), now())
                    """, runId, scanDate, scanDate.minusDays(1), "bars-" + index);
            insertItem(runId, scanDate.minusDays(1));
        }
        UUID buildingRun = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO first_board_baseline_run (
                  run_id, scan_trade_date, baseline_trade_date, rule_version, bar_data_version,
                  status, universe_count, supported_count, excluded_name_count,
                  insufficient_history_count, item_count, started_at
                ) VALUES (?, ?, ?, 'v1', 'building', 'BUILDING', 1, 1, 0, 0, 0, now())
                """, buildingRun, start.minusDays(10), start.minusDays(11));
        jdbc.update("""
                INSERT INTO market_kline_history (
                  observation_id, symbol, trade_date, bar_type, source_name, observed_at
                ) VALUES ('shared-row', '600000', DATE '2025-01-01', 'DAY_QFQ', 'test', now())
                """);

        new FirstBoardRetentionService(jdbc).cleanup();

        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT trade_date) FROM first_board_daily_bar", Integer.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM first_board_market_day", Integer.class)).isEqualTo(130);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT scan_trade_date)
                FROM first_board_baseline_run WHERE status = 'READY'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM first_board_baseline_item", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM first_board_baseline_run WHERE run_id = ?", Integer.class, buildingRun))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM market_kline_history", Integer.class)).isEqualTo(1);
    }

    private void insertItem(UUID runId, LocalDate baselineDate) {
        jdbc.update("""
                INSERT INTO first_board_baseline_item (
                  run_id, symbol, company_name, board, baseline_date, previous_close,
                  ma10, ma20, ma20_slope, rsi14, macd_histogram, technical_pass_count,
                  previous_day_volume_ratio, recent_five_max_volume_ratio, upper_shadow_ratio,
                  three_day_close_range, previous_limit_up
                ) VALUES (?, '600000', '测试', 'MAIN', ?, 10, 10, 10, 1, 55, 1, 5, 1, 1, 1, 1, FALSE)
                """, runId, baselineDate);
    }
}
