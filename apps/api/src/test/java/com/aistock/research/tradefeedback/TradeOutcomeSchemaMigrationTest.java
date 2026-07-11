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
                "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS MARKET_TIMESTAMP TIMESTAMP WITH TIME ZONE");
    }
}
