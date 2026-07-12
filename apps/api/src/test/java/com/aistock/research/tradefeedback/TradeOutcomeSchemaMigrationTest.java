package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeOutcomeSchemaMigrationTest {

    @Test
    void bothSchemasContainPortableOutcomeProvenanceMigrations() throws IOException {
        assertPortableMigration(Path.of("src/main/resources/schema.sql"));
        assertPortableMigration(Path.of("../../infra/db/init/001_schema.sql"));
    }

    @Test
    void h2UpgradeBackfillsLegacyRevisionsAndRemainsIdempotent() throws Exception {
        String databaseName = "trade_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(
                    legacyTradeSchema().getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/schema.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/schema.sql"));

            List<Long> sequences = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT revision_sequence
                         FROM strategy_trade_fill_revision
                         ORDER BY revision_sequence
                         """)) {
                while (rows.next()) {
                    sequences.add(rows.getLong(1));
                }
            }
            assertThat(sequences).containsExactly(1L, 2L);

            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("""
                         SELECT outcome_dirty
                         FROM strategy_trade_case
                         WHERE case_id = 'case-legacy'
                         """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).isFalse();
            }

            assertThatThrownBy(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO strategy_trade_fill_revision (
                              revision_id, fill_id, case_id, revision_sequence, revision_type,
                              side, executed_at, price, quantity, created_at
                            ) VALUES (
                              'revision-duplicate', 'fill-legacy', 'case-legacy', 1, 'CORRECTION',
                              'BUY', TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00', 38, 100,
                              TIMESTAMP WITH TIME ZONE '2026-07-01 02:00:02+00'
                            )
                            """);
                }
            }).isInstanceOf(java.sql.SQLException.class);
        }
    }

    private void assertPortableMigration(Path schemaPath) throws IOException {
        String schema = Files.readString(schemaPath).toUpperCase();
        assertThat(schema).contains(
                "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS SOURCE_NAME VARCHAR(128)",
                "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS MARKET_TIMESTAMP TIMESTAMP WITH TIME ZONE",
                "ALTER TABLE STRATEGY_TRADE_CASE ADD COLUMN IF NOT EXISTS RECOMMENDATION_VERIFIED BOOLEAN NOT NULL DEFAULT FALSE",
                "ALTER TABLE STRATEGY_TRADE_CASE ADD COLUMN IF NOT EXISTS OUTCOME_DIRTY BOOLEAN NOT NULL DEFAULT FALSE",
                "CREATE TABLE IF NOT EXISTS STRATEGY_TRADE_FILL_REVISION",
                "ALTER TABLE STRATEGY_TRADE_FILL_REVISION ADD COLUMN IF NOT EXISTS REVISION_SEQUENCE BIGINT",
                "CREATE UNIQUE INDEX IF NOT EXISTS UK_TRADE_FILL_REVISION_SEQUENCE",
                "ON STRATEGY_OUTCOME_SNAPSHOT(BASELINE_TYPE, HORIZON, STATUS, CASE_ID)",
                "ON STRATEGY_TRADE_FILL(CASE_ID, SIDE, EXECUTED_AT, CREATED_AT, FILL_ID)",
                "ON STRATEGY_TRADE_FILL_REVISION(CASE_ID, FILL_ID, REVISION_SEQUENCE)",
                "ON STRATEGY_TRADE_CASE(OUTCOME_DIRTY, STATUS, UPDATED_AT, CASE_ID)");
    }

    private String legacyTradeSchema() {
        return """
                CREATE TABLE strategy_trade_case (
                  case_id VARCHAR(36) PRIMARY KEY,
                  recommendation_fingerprint VARCHAR(64) NOT NULL UNIQUE,
                  decision_id VARCHAR(36),
                  symbol VARCHAR(6) NOT NULL,
                  company_name VARCHAR(128) NOT NULL,
                  source_module VARCHAR(64) NOT NULL,
                  recommendation_action VARCHAR(64) NOT NULL,
                  recommendation_score NUMERIC(8, 2),
                  rule_version VARCHAR(64) NOT NULL,
                  recommended_price NUMERIC(20, 6) NOT NULL,
                  recommended_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  recommendation_payload_json TEXT NOT NULL,
                  recommendation_verified BOOLEAN NOT NULL DEFAULT FALSE,
                  recommendation_attestation_id VARCHAR(64),
                  status VARCHAR(32) NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                );
                CREATE TABLE strategy_trade_fill (
                  fill_id VARCHAR(36) PRIMARY KEY,
                  case_id VARCHAR(36) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  price NUMERIC(20, 6) NOT NULL,
                  quantity BIGINT NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                );
                CREATE TABLE strategy_trade_fill_revision (
                  revision_id VARCHAR(36) PRIMARY KEY,
                  fill_id VARCHAR(36) NOT NULL,
                  case_id VARCHAR(36) NOT NULL,
                  revision_type VARCHAR(16) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  price NUMERIC(20, 6) NOT NULL,
                  quantity BIGINT NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL
                );
                INSERT INTO strategy_trade_case (
                  case_id, recommendation_fingerprint, symbol, company_name, source_module,
                  recommendation_action, rule_version, recommended_price, recommended_at,
                  recommendation_payload_json, status, created_at, updated_at
                ) VALUES (
                  'case-legacy', 'fingerprint-legacy', '002714', '牧原股份', 'MISPRICING',
                  '观察', 'v1', 35, TIMESTAMP WITH TIME ZONE '2026-07-01 00:00:00+00',
                  '{}', 'HOLDING', TIMESTAMP WITH TIME ZONE '2026-07-01 00:00:00+00',
                  TIMESTAMP WITH TIME ZONE '2026-07-01 00:00:00+00'
                );
                INSERT INTO strategy_trade_fill (
                  fill_id, case_id, side, executed_at, price, quantity, created_at, updated_at
                ) VALUES (
                  'fill-legacy', 'case-legacy', 'BUY', TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00',
                  35, 100, TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00',
                  TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00'
                );
                INSERT INTO strategy_trade_fill_revision (
                  revision_id, fill_id, case_id, revision_type, side, executed_at, price, quantity, created_at
                ) VALUES
                  ('revision-1', 'fill-legacy', 'case-legacy', 'CORRECTION', 'BUY',
                   TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00', 36, 100,
                   TIMESTAMP WITH TIME ZONE '2026-07-01 02:00:00+00'),
                  ('revision-2', 'fill-legacy', 'case-legacy', 'VOID', 'BUY',
                   TIMESTAMP WITH TIME ZONE '2026-07-01 01:00:00+00', 36, 100,
                   TIMESTAMP WITH TIME ZONE '2026-07-01 02:00:01+00');
                """;
    }
}
