package com.aistock.research.history;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCompatibilityTest {

    @Test
    void productionProfileDefaultsTheDatasourceDriverToPostgres() throws Exception {
        try (var input = getClass().getResourceAsStream("/application-prod.yml")) {
            assertThat(input).isNotNull();
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(configuration).contains("driver-class-name: ${JDBC_DATABASE_DRIVER:org.postgresql.Driver}");
        }
    }

    @Test
    void sharedSchemaAvoidsH2OnlyClobTypeSoPostgresCanInitialize() throws Exception {
        try (var input = getClass().getResourceAsStream("/schema.sql")) {
            assertThat(input).isNotNull();
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(schema.toUpperCase()).doesNotContain(" CLOB");
            assertThat(schema).contains("payload_json TEXT NOT NULL");
        }
    }
}
