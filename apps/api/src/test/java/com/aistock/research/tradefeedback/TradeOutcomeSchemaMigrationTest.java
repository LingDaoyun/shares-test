package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOutcomeSchemaMigrationTest {

    @Test
    void bothSchemasContainPortableOutcomeProvenanceMigrations() throws IOException {
        assertPortableMigration(Path.of("src/main/resources/schema.sql"));
        assertPortableMigration(Path.of("../../infra/db/init/001_schema.sql"));
    }

    private void assertPortableMigration(Path schemaPath) throws IOException {
        String schema = Files.readString(schemaPath).toUpperCase();
        assertThat(schema).contains(
                "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS SOURCE_NAME VARCHAR(128)",
                "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS MARKET_TIMESTAMP TIMESTAMP WITH TIME ZONE",
                "ALTER TABLE STRATEGY_TRADE_CASE ADD COLUMN IF NOT EXISTS RECOMMENDATION_VERIFIED BOOLEAN NOT NULL DEFAULT FALSE",
                "CREATE TABLE IF NOT EXISTS STRATEGY_TRADE_FILL_REVISION",
                "ON STRATEGY_OUTCOME_SNAPSHOT(BASELINE_TYPE, HORIZON, STATUS, CASE_ID)",
                "ON STRATEGY_TRADE_FILL(CASE_ID, SIDE, EXECUTED_AT, CREATED_AT, FILL_ID)",
                "ON STRATEGY_TRADE_FILL_REVISION(CASE_ID, FILL_ID, CREATED_AT, REVISION_ID)",
                "ON STRATEGY_TRADE_CASE(STATUS, UPDATED_AT, CASE_ID)");
    }
}
