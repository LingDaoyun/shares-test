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
                            "five_day_scan",
                            "short_term_daily_snapshot",
                            "short_term_scan_result_cache",
                            "market_kline_history",
                            "runtime_config_section",
                            "short_term_signal_observation",
                            "strategy_trade_case",
                            "trend_analysis_run",
                            "policy_ai_review_history",
                            "policy_daily_snapshot",
                            "gov_policy_archive"
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
                            "idx_trade_case_dirty_refresh",
                            "idx_five_day_scan_history"
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
    void upgradesVersionFourWithoutChangingExistingResearchData() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        String schema = "flyway_fiveday_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("4")).load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO runtime_config_section (section_key, payload_json, revision, updated_at)
                        VALUES ('existing-research', '{"keep":true}', 7, TIMESTAMPTZ '2026-09-07 16:00:00+08')
                        """);
            }
            Flyway current = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(6);
            assertThat(current.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                try (var rows = connection.createStatement().executeQuery("""
                        SELECT payload_json, revision, updated_at FROM runtime_config_section
                        WHERE section_key = 'existing-research'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("payload_json")).isEqualTo("{\"keep\":true}");
                    assertThat(rows.getLong("revision")).isEqualTo(7);
                    assertThat(rows.getTimestamp("updated_at").toInstant())
                            .isEqualTo(java.time.Instant.parse("2026-09-07T08:00:00Z"));
                }
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM five_day_scan")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM short_term_daily_snapshot")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
                var dailyStore = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshotStore(
                        new org.springframework.jdbc.core.JdbcTemplate(
                                new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
                var date = java.time.LocalDate.of(2026, 10, 15);
                var start = java.time.Instant.parse("2026-10-15T06:00:00Z");
                var running = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(
                        date, start, start, null, "RUNNING", "initial", null, null, java.util.List.of());
                assertThat(dailyStore.claim(running)).isTrue();
                assertThat(dailyStore.claim(running)).isFalse();
                assertThat(dailyStore.claimRetry(running)).isFalse();
                dailyStore.finish(new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(
                        date, start, start, start.plusSeconds(60), "FAILED", "source failed", null, null, java.util.List.of()));
                var retry = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(
                        date, start, start.plusSeconds(600), null, "RUNNING", "retry", null, null, java.util.List.of());
                assertThat(dailyStore.claimRetry(retry)).isTrue();
                assertThat(dailyStore.claimRetry(retry)).isFalse();
                dailyStore.finish(new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(
                        date, start, start, start.plusSeconds(601), "FAILED", "late old worker", null, null, java.util.List.of()));
                assertThat(dailyStore.latest().orElseThrow().status()).isEqualTo("RUNNING");
                assertThat(dailyStore.latest().orElseThrow().startedAt()).isEqualTo(start.plusSeconds(600));
                dailyStore.finish(new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(
                        date, start, retry.startedAt(), start.plusSeconds(660), "READY", "saved", null, null, java.util.List.of()));
                assertThat(dailyStore.claimRetry(retry)).isFalse();
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void upgradesVersionFiveWithoutChangingExistingFiveDayScan() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");

        String schema = "flyway_daily_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO five_day_scan (scan_id, signal_date, rule_version, include_growth,
                            include_star, forward_eligible, snapshot_json, outcomes_json, captured_at)
                        VALUES ('existing-five-day', DATE '2026-10-14', 'existing-rule', TRUE, FALSE,
                            TRUE, '{"keep":true}', '{"settled":1}', TIMESTAMPTZ '2026-10-14 16:00:00+08')
                        """);
            }
            Flyway current = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(5);
            assertThat(current.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("10"));
            assertThat(current.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                try (var rows = connection.createStatement().executeQuery("""
                        SELECT scan_id, signal_date, rule_version, include_growth, include_star, forward_eligible,
                            snapshot_json->>'keep' AS keep, outcomes_json->>'settled' AS settled, captured_at
                        FROM five_day_scan
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("scan_id")).isEqualTo("existing-five-day");
                    assertThat(rows.getDate("signal_date").toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 14));
                    assertThat(rows.getString("rule_version")).isEqualTo("existing-rule");
                    assertThat(rows.getBoolean("include_growth")).isTrue();
                    assertThat(rows.getBoolean("include_star")).isFalse();
                    assertThat(rows.getBoolean("forward_eligible")).isTrue();
                    assertThat(rows.getString("keep")).isEqualTo("true");
                    assertThat(rows.getString("settled")).isEqualTo("1");
                    assertThat(rows.getTimestamp("captured_at").toInstant())
                            .isEqualTo(java.time.Instant.parse("2026-10-14T08:00:00Z"));
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM short_term_daily_snapshot")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void versionSevenSeedsOnlyTheLatestCompletedResultAndManualCacheDoesNotClaimDailyDate() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        String schema = "flyway_result_cache_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("6")).load().migrate();
            java.util.Map<String,String> original = new java.util.LinkedHashMap<>();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                for (int day = 12; day <= 16; day++) {
                    String date = "2026-10-" + day;
                    String status = day == 12 ? "READY" : day == 13 ? "PARTIAL" : day == 14 ? "FAILED" : day == 15 ? "RUNNING" : "READY";
                    String started = date + "T06:00:00Z";
                    String finished = day >= 15 ? null : date + "T06:01:00Z";
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var payload = mapper.createObjectNode();
                    payload.put("tradeDate", date); payload.put("startedAt", started); payload.put("finishedAt", finished);
                    payload.put("status",status); payload.put("message","original-" + day);
                    payload.putArray("dataGaps");
                    var report = payload.putObject("report"); report.put("scope","original-" + day);
                    report.putObject("tradeCaptureTokens").put("600001","must-not-persist-in-cache");
                    String json = mapper.writeValueAsString(payload);
                    original.put(date,json);
                    jdbc.update("INSERT INTO short_term_daily_snapshot(trade_date,status,started_at,finished_at,payload_json) VALUES (?,?,?,?,?)",
                            java.time.LocalDate.parse(date),status,java.sql.Timestamp.from(java.time.Instant.parse(started)),
                            finished == null ? null : java.sql.Timestamp.from(java.time.Instant.parse(finished)),json);
                }
            }
            Flyway current = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(4);
            assertThat(current.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("10"));
            assertThat(current.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
                var cache = new com.aistock.research.shortterm.schedule.ShortTermScanResultCache(jdbc,mapper);
                var seeded = cache.latest().orElseThrow();
                assertThat(seeded.tradeDate()).isEqualTo(java.time.LocalDate.parse("2026-10-13"));
                assertThat(seeded.status()).isEqualTo("PARTIAL");
                assertThat(seeded.runId()).isEqualTo("daily:2026-10-13:2026-10-13T06:00:00Z");
                assertThat(seeded.report().tradeCaptureTokens()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT payload_json FROM short_term_scan_result_cache WHERE id=1",String.class))
                        .doesNotContain("must-not-persist-in-cache").doesNotContain("tradeCaptureTokens");
                for (var row : original.entrySet()) assertThat(jdbc.queryForObject(
                        "SELECT payload_json FROM short_term_daily_snapshot WHERE trade_date=?",String.class,java.time.LocalDate.parse(row.getKey())))
                        .isEqualTo(row.getValue());

                var date = java.time.LocalDate.parse("2026-10-19");
                var newerStart = java.time.Instant.parse("2026-10-19T06:10:00Z");
                var newer = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(date,null,newerStart,newerStart.plusSeconds(60),
                        "READY","new manual result",seeded.report(),null,java.util.List.of(),"manual-new");
                assertThat(cache.save(newer)).isTrue();
                var lateOld = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(date,null,newerStart.minusSeconds(60),newerStart.plusSeconds(120),
                        "PARTIAL","late old result",null,null,java.util.List.of(),"manual-old");
                assertThat(cache.save(lateOld)).isFalse();
                var sameStartOtherRun = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(date,null,newerStart,newerStart.plusSeconds(180),
                        "READY","same-time other run",null,null,java.util.List.of(),"manual-other");
                assertThat(cache.save(sameStartOtherRun)).isFalse();
                assertThat(cache.latest().orElseThrow().runId()).isEqualTo("manual-new");
                assertThat(cache.latest().orElseThrow().report().scope()).isEqualTo(seeded.report().scope());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM short_term_scan_result_cache",Integer.class)).isEqualTo(1);
                var daily = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshotStore(jdbc,mapper);
                assertThat(daily.claim(new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(date,newerStart,newerStart,
                        null,"RUNNING","scheduled",null,null,java.util.List.of()))).isTrue();
                var proxyFactory = new org.springframework.aop.framework.ProxyFactory(daily);
                proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),
                        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                var transactionalDaily = (com.aistock.research.shortterm.schedule.ShortTermDailySnapshotStore) proxyFactory.getProxy();
                var failedCache = org.mockito.Mockito.mock(com.aistock.research.shortterm.schedule.ShortTermScanResultCache.class);
                org.mockito.Mockito.when(failedCache.save(org.mockito.ArgumentMatchers.any()))
                        .thenThrow(new IllegalStateException("cache write failed"));
                var scheduledResult = new com.aistock.research.shortterm.schedule.ShortTermDailySnapshot(date,newerStart,newerStart,
                        newerStart.plusSeconds(60),"READY","scheduled result",null,null,java.util.List.of());
                assertThatThrownBy(() -> transactionalDaily.finishAndPublish(scheduledResult,failedCache))
                        .isInstanceOf(IllegalStateException.class).hasMessage("cache write failed");
                assertThat(daily.latest().orElseThrow().status()).isEqualTo("RUNNING");
                assertThat(daily.latest().orElseThrow().finishedAt()).isNull();
                assertThat(cache.latest().orElseThrow().runId()).isEqualTo("manual-new");
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
