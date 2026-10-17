package com.aistock.research.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PolicyDailyPostgresPersistenceTest {
    @Test
    void roundTripsDailySnapshotAndAiHistoryAgainstPostgresAfterMigrations() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        String schema = "policy_persistence_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
                PolicyDailyRepository daily = new PolicyDailyRepository(jdbc, mapper);
                PolicyAiReviewStore history = new PolicyAiReviewStore(jdbc, mapper);
                Instant started = Instant.parse("2026-10-17T00:30:00Z");
                PolicySignal evidence = new PolicySignal("政府网", "规划", "产业发展规划标题", 80,
                        "https://www.gov.cn/policy/roundtrip", "2026-10-16T16:00:00Z");
                PolicyCompanyCandidate company = new PolicyCompanyCandidate("000001", "候选公司", "设备", "设备环节",
                        "行业候选", List.of("主营待核实"), BigDecimal.valueOf(70), "待核实", null, null, null, null,
                        "不荐股", List.of("核验主营收入"));
                PolicyTheme theme = new PolicyTheme("DIGITAL_INFRA", "算力", "国家级", "2-12周", BigDecimal.valueOf(80),
                        List.of("设备"), List.of(evidence), List.of("订单未兑现"), List.of(company));
                PolicyDailyState running = new PolicyDailyState(new PolicyDailySnapshot("RUNNING", PolicyDailySnapshot.SCHEDULED_TIME,
                        started, null, List.of(theme), List.of(), List.of("研判中")), LocalDate.of(2026, 10, 17), null);
                assertThat(daily.load()).isEmpty();
                daily.save(running);
                assertThat(daily.load()).contains(running);
                PolicyDailyState completed = new PolicyDailyState(new PolicyDailySnapshot("READY", PolicyDailySnapshot.SCHEDULED_TIME,
                        started, started.plusSeconds(60), List.of(theme), List.of(theme.themeCode()), List.of()),
                        LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17));
                daily.save(completed);
                assertThat(new PolicyDailyRepository(jdbc, mapper).load()).contains(completed);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_daily_snapshot", Integer.class)).isEqualTo(1);
                var themes = mock(PolicyThemeService.class);
                var reviews = mock(PolicyAiReviewService.class);
                var restarted = new PolicyDailyService(themes, reviews, new PolicyDailyRepository(jdbc, mapper),
                        Clock.fixed(started.plusSeconds(120), ZoneOffset.UTC), Runnable::run, 8);
                assertThat(restarted.current()).isEqualTo(completed.snapshot());
                restarted.checkSchedule();
                verifyNoInteractions(themes, reviews);

                PolicyAiReview older = review(started, evidence);
                PolicyAiReview newer = review(started.plusSeconds(60), evidence);
                history.append(older, "a".repeat(64), "fixture-v1");
                history.append(newer, "b".repeat(64), "fixture-v2");
                var restored = new PolicyAiReviewStore(jdbc, mapper);
                assertThat(restored.history("DIGITAL_INFRA")).containsExactly(newer, older);
                assertThat(restored.history("UNRELATED")).isEmpty();
                assertThat(restored.latest("DIGITAL_INFRA", "b".repeat(64), "fixture", "model", "fixture-v2", started))
                        .contains(newer);
                assertThat(restored.latest("DIGITAL_INFRA", "b".repeat(64), "fixture", "other-model", "fixture-v2", started)).isEmpty();
                assertThat(restored.latest("DIGITAL_INFRA", "b".repeat(64), "fixture", "model", "fixture-v1", started)).isEmpty();
                assertThat(restored.latest("DIGITAL_INFRA", "b".repeat(64), "fixture", "model", "fixture-v2", started.plusSeconds(61))).isEmpty();
                assertThatThrownBy(() -> restored.append(PolicyAiReview.unavailable("DIGITAL_INFRA", "算力", "failed"),
                        "c".repeat(64), "fixture-v2")).isInstanceOf(IllegalArgumentException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_ai_review_history", Integer.class)).isEqualTo(2);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private PolicyAiReview review(Instant reviewedAt, PolicySignal evidence) {
        return new PolicyAiReview("DIGITAL_INFRA", "算力", "fixture", "model", 80, "POSITIVE", "政策利好",
                "EARLY", "发酵初期", "条件性传导逻辑", List.of(), List.of(), List.of(), List.of(), 75, reviewedAt, List.of(),
                new PolicyTrendPlan("观察建设进度", "政策增量待核实", List.of("投资到设备需求"), List.of(), List.of(),
                        List.of("预算落地"), List.of("项目延期"), PolicyTrendPlan.MARKET_CONFIRMATION,
                        List.of(new PolicyCompanyAssessment("000001", "候选公司", "INDUSTRY", "产业归属关联", "核验主营和订单"))),
                List.of(evidence));
    }
}
