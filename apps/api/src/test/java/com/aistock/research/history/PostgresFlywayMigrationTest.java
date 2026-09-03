package com.aistock.research.history;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresFlywayMigrationTest {

    @Test
    void migratesAnEmptyPostgresSchemaAndIsIdempotent() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");

        String schema = "flyway_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, username, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();

            assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(0);
            assertThat(flyway.migrate().migrationsExecuted).isZero();

            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.prepareStatement("""
                         SELECT table_name
                         FROM information_schema.tables
                         WHERE table_schema = ?
                           AND table_type = 'BASE TABLE'
                         ORDER BY table_name
                         """)) {
                statement.setString(1, schema);
                try (var rows = statement.executeQuery()) {
                    Set<String> tables = new TreeSet<>();
                    while (rows.next()) tables.add(rows.getString(1));
                    assertThat(tables).contains(
                            "flyway_schema_history",
                            "first_board_daily_bar",
                            "first_board_market_day",
                            "first_board_baseline_run",
                            "first_board_baseline_item",
                            "leader_daily_snapshot",
                            "leader_outcome",
                            "leader_ai_review",
                            "market_kline_history",
                            "runtime_config_section",
                            "short_term_signal_observation",
                            "strategy_trade_case",
                            "trend_analysis_run"
                    );
                }
            }

            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.prepareStatement("""
                         SELECT indexname
                         FROM pg_indexes
                         WHERE schemaname = ?
                         """)) {
                statement.setString(1, schema);
                try (var rows = statement.executeQuery()) {
                    Set<String> indexes = new TreeSet<>();
                    while (rows.next()) indexes.add(rows.getString(1));
                    assertThat(indexes).contains(
                            "uk_trade_fill_revision_sequence",
                            "idx_trade_fill_feedback_lookup",
                            "idx_trade_fill_revision_sequence_projection",
                            "idx_trade_outcome_feedback_lookup",
                            "idx_trade_case_dirty_refresh"
                    );
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void repairsUnsafeReadyRowsAndRejectsFutureIncompleteReadyRows() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");

        String schema = "flyway_state_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("2"))
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO first_board_market_day (
                          trade_date, source_trade_date, status,
                          expected_symbol_count, written_symbol_count, failed_symbol_count,
                          limit_up_pool_complete, bar_data_version, started_at, completed_at,
                          next_retry_at, error_summary
                        ) VALUES (
                          DATE '2026-08-28', DATE '2026-08-28', 'READY',
                          2, 1, 1, TRUE, 'partial', now(), now(), NULL, NULL
                        )
                        """);
            }

            Flyway.configure()
                    .dataSource(url, username, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                try (var rows = connection.createStatement().executeQuery("""
                        SELECT status, failed_symbol_count, next_retry_at, error_summary
                        FROM first_board_market_day
                        WHERE trade_date = DATE '2026-08-28'
                        """)) {
                    rows.next();
                    assertThat(rows.getString("status")).isEqualTo("FAILED");
                    assertThat(rows.getInt("failed_symbol_count")).isPositive();
                    assertThat(rows.getObject("next_retry_at")).isNotNull();
                    assertThat(rows.getString("error_summary")).contains("Flyway V3");
                }
            }

            assertThatThrownBy(() -> {
                try (var connection = DriverManager.getConnection(url, username, password)) {
                    connection.setSchema(schema);
                    connection.createStatement().execute("""
                            INSERT INTO first_board_market_day (
                              trade_date, source_trade_date, status,
                              expected_symbol_count, written_symbol_count, failed_symbol_count,
                              limit_up_pool_complete, bar_data_version, started_at, completed_at,
                              next_retry_at, error_summary
                            ) VALUES (
                              DATE '2026-08-29', DATE '2026-08-29', 'READY',
                              2, 1, 1, TRUE, 'partial', now(), now(), NULL, NULL
                            )
                            """);
                }
            }).isInstanceOf(SQLException.class);
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
