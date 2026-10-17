package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static com.aistock.research.fiveday.FiveDayModels.*;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static com.aistock.research.fiveday.FiveDayOutcomeCalculatorTest.candidate;
import static com.aistock.research.fiveday.FiveDayOutcomeCalculatorTest.snapshot;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayVersionHistoryTest {
    @Test
    void versionsPersistIndependentlyAndContextSurvivesASecondServiceInstance() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String user = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        assertThat(url).as("explicit isolated test database is required").isNotBlank();
        assertThat(url).contains("fiveday_test");
        String schema = "five_day_versions_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(url, user, password)) { c.createStatement().execute("CREATE SCHEMA " + schema); }
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            var jdbc = new JdbcTemplate(ds);
            var mapper = new ObjectMapper().findAndRegisterModules();
            var clock = new TradingClockService(Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneId.of("Asia/Shanghai")));
            var store = mock(FirstBoardDailyBarStore.class);
            var history = new FiveDayHistoryService(jdbc, mapper, store, clock);
            var base = snapshot(List.of(candidate("600001", true, true)), true, false, false);
            var context = new Context(base.generatedAt(), new Market("NORMAL", 200, 200, bd("100"), bd("60"), bd("0.8"), bd("0"), List.of()),
                    List.of(), List.of(new CandidateAssessment("600001", "种植业", "WATCH", new Turning(false, bd("0"), bd("40"), List.of()),
                    new EventReview("NO_TITLE_RISK", List.of(), List.of()), List.of(), List.of("尚未转强"))), List.of(), "READY", List.of());
            var next = new Snapshot(base.status(), VERSION_V2, base.signalDate(), base.generatedAt(), true, false, false,
                    "v2 original", base.rules(), base.coverage(), base.candidates(), List.of(), context);
            history.capture(base); history.capture(next);
            history.capture(new Snapshot(base.status(), VERSION, base.signalDate(), base.generatedAt(), true, false, false,
                    "must not replace v1", base.rules(), base.coverage(), List.of(), List.of()));
            history.capture(new Snapshot(next.status(), VERSION_V2, next.signalDate(), next.generatedAt(), true, false, false,
                    "must not replace", next.rules(), next.coverage(), List.of(), List.of(), context));
            var reloaded = new FiveDayHistoryService(jdbc, mapper, store, clock);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM five_day_scan", Integer.class)).isEqualTo(2);
            assertThat(reloaded.latest(base.signalDate(), false, false).orElseThrow()).isEqualTo(base);
            Snapshot restored = reloaded.latest(base.signalDate(), false, false, VERSION_V2).orElseThrow();
            assertThat(restored.message()).isEqualTo("v2 original");
            assertThat(restored.context()).isEqualTo(context);
            assertThat(reloaded.history(VERSION).rows()).hasSize(1);
            History v2 = reloaded.history(VERSION_V2);
            assertThat(v2.rows()).singleElement().satisfies(row -> assertThat(row.decision()).isEqualTo("WATCH"));
            assertThat(v2.cohorts().get(0).samples()).isEqualTo(1);
            assertThat(v2.cohorts().get(1).samples()).isZero();
            assertThat(v2.cohorts()).extracting(Cohort::label)
                    .containsExactly("主线量价对照（资金完整）", "执行条件齐备（对照子集）");
            verifyNoInteractions(store);

            String originalV1 = jdbc.queryForObject("SELECT snapshot_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION);
            String originalV2 = jdbc.queryForObject("SELECT snapshot_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION_V2);
            when(store.recentBars(anyString(), any(LocalDate.class), eq(180))).thenReturn(List.of());
            reloaded.refresh(VERSION_V2);
            assertThat(jdbc.queryForObject("SELECT outcomes_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION)).isEqualTo("{}");
            assertThat(jdbc.queryForObject("SELECT outcomes_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION_V2)).contains("600001");
            assertThat(jdbc.queryForObject("SELECT snapshot_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION)).isEqualTo(originalV1);
            assertThat(jdbc.queryForObject("SELECT snapshot_json::text FROM five_day_scan WHERE rule_version = ?", String.class, VERSION_V2)).isEqualTo(originalV2);
        } finally {
            try (var c = DriverManager.getConnection(url, user, password)) { c.createStatement().execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
    private static java.math.BigDecimal bd(String s) { return new java.math.BigDecimal(s); }
}
