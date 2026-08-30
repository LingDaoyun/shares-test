package com.aistock.research.history;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCompatibilityTest {

    @Test
    void everyApplicationProfileUsesPostgresOnly() throws Exception {
        for (String resource : List.of("/application.yml", "/application-local.yml", "/application-prod.yml")) {
            try (var input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

                assertThat(configuration).as(resource)
                        .contains("jdbc:postgresql://")
                        .contains("org.postgresql.Driver")
                        .doesNotContainIgnoringCase("jdbc:h2:")
                        .doesNotContainIgnoringCase("org.h2.Driver")
                        .doesNotContainIgnoringCase("h2:");
            }
        }
    }

    @Test
    void flywayOwnsSchemaInitialization() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(configuration)
                    .contains("ddl-auto: validate")
                    .contains("mode: never")
                    .contains("flyway:")
                    .contains("enabled: true");
        }

        assertThat(getClass().getResource("/schema.sql")).isNull();
        try (var input = getClass().getResourceAsStream("/db/migration/V1__baseline_schema.sql")) {
            assertThat(input).isNotNull();
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
            assertThat(schema).contains(
                    "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS SOURCE_NAME VARCHAR(128)",
                    "ALTER TABLE STRATEGY_OUTCOME_SNAPSHOT ADD COLUMN IF NOT EXISTS MARKET_TIMESTAMP TIMESTAMP WITH TIME ZONE",
                    "CREATE UNIQUE INDEX IF NOT EXISTS UK_TRADE_FILL_REVISION_SEQUENCE",
                    "ON STRATEGY_TRADE_FILL(CASE_ID, SIDE, EXECUTED_AT, CREATED_AT, FILL_ID)",
                    "ON STRATEGY_TRADE_FILL_REVISION(CASE_ID, FILL_ID, REVISION_SEQUENCE)",
                    "ON STRATEGY_TRADE_CASE(OUTCOME_DIRTY, STATUS, UPDATED_AT, CASE_ID)");
        }
    }

    @Test
    void composeConnectsApiToExistingHostPostgresWithoutOwningDatabaseStorage() throws Exception {
        String compose = Files.readString(Path.of("../../docker-compose.yml"));

        assertThat(compose)
                .contains("jdbc:postgresql://host.docker.internal:5432/aistock")
                .contains("org.postgresql.Driver")
                .doesNotContainIgnoringCase("jdbc:h2:")
                .doesNotContain("api-data:/data")
                .doesNotContain("  postgres:")
                .doesNotContain("ai-stock-postgres")
                .doesNotContain("postgres-data:");
    }

    @Test
    void apiRuntimeHasNoH2Dependency() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .doesNotContain("<groupId>com.h2database</groupId>")
                .doesNotContain("<artifactId>h2</artifactId>");
    }
}
