package com.aistock.research.leader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderSnapshotStoreTest {

    private String baseUrl;
    private String username;
    private String password;
    private String schema;
    private JdbcTemplate jdbc;
    private LeaderSnapshotStore store;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = System.getenv("AISTOCK_TEST_JDBC_URL");
        username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(baseUrl != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        schema = "leader_store_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(baseUrl, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(baseUrl, username, password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String schemaUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, username, password));
        store = new LeaderSnapshotStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema == null) return;
        try (var connection = DriverManager.getConnection(baseUrl, username, password)) {
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void duplicateTradeDateAndRuleVersionReturnsExistingSnapshotId() {
        PotentialLeaderSnapshot snapshot = snapshot();

        UUID first = store.saveSnapshotIfAbsent(snapshot);
        UUID second = store.saveSnapshotIfAbsent(snapshot);
        store.initializeOutcomes(first, snapshot);
        store.initializeOutcomes(first, snapshot);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leader_daily_snapshot", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leader_outcome", Integer.class))
                .isEqualTo(1);
        assertThat(store.pendingOutcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.snapshotId()).isEqualTo(first);
            assertThat(outcome.lane()).isEqualTo(PotentialLeaderSnapshot.Lane.POTENTIAL);
            assertThat(outcome.symbol()).isEqualTo("600000");
            assertThat(outcome.industry()).isEqualTo("半导体");
            assertThat(outcome.sampleClose()).isEqualByComparingTo("10");
            assertThat(outcome.directionConstituents()).containsExactly(
                    "600000", "600001", "600002", "600003", "600004");
        });
    }

    private PotentialLeaderSnapshot snapshot() {
        LocalDate date = LocalDate.parse("2026-09-03");
        PotentialLeaderSnapshot.Direction direction = new PotentialLeaderSnapshot.Direction(
                "半导体", 1, bd("82"), bd("70"), bd("5"), 3, 1, 3,
                bd("500000000"), List.of("600000", "600001", "600002", "600003", "600004"));
        PotentialLeaderSnapshot.Candidate candidate = new PotentialLeaderSnapshot.Candidate(
                PotentialLeaderSnapshot.Lane.POTENTIAL, 1, PotentialLeaderSnapshot.Grade.A, bd("82"),
                "600000", "测试股份", "半导体", "MAIN", bd("10"), bd("5"), bd("1.5"),
                bd("5"), bd("100000000"), bd("8000000000"), bd("9.8"), bd("9.5"),
                bd("0.02"), bd("101"), false, 0, null, null, 0, BigDecimal.ZERO,
                List.of(), List.of(), List.of(), new PotentialLeaderSnapshot.DragonTigerEvidence(
                PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED, null, BigDecimal.ZERO,
                List.of(), List.of()), null, "candidate-hash");
        return new PotentialLeaderSnapshot(
                PotentialLeaderSnapshot.Status.READY, date, date,
                Instant.parse("2026-09-03T07:00:00Z"), null,
                Instant.parse("2026-09-03T07:10:00Z"), false, LeaderRuleSet.RULE_VERSION,
                new PotentialLeaderSnapshot.BoardOptions(false, false),
                new PotentialLeaderSnapshot.MarketSummary(60, 10, 3, bd("85"), bd("30"), "情绪强势"),
                List.of(direction), List.of(candidate), List.of(),
                new PotentialLeaderSnapshot.Coverage(5000, 4800, 4000, 3500, 1, 1, 0, 0, 0),
                List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
