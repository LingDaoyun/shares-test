package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FirstBoardPersistenceStoreTest {

    private String baseUrl;
    private String username;
    private String password;
    private String schema;
    private JdbcTemplate jdbc;
    private PostgresFirstBoardDailyBarStore bars;
    private PostgresFirstBoardBaselineStore baselines;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = System.getenv("AISTOCK_TEST_JDBC_URL");
        username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(baseUrl != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        schema = "first_board_store_" + UUID.randomUUID().toString().replace("-", "");
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
        var dataSource = new DriverManagerDataSource(schemaUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        bars = new PostgresFirstBoardDailyBarStore(jdbc, new NamedParameterJdbcTemplate(dataSource));
        baselines = new PostgresFirstBoardBaselineStore(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema == null) {
            return;
        }
        try (var connection = DriverManager.getConnection(baseUrl, username, password)) {
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void importsOnlyLatestObservedVersionPerSymbolAndDate() {
        jdbc.update("""
                INSERT INTO market_kline_history (
                  observation_id, symbol, trade_date, bar_type, open_price, close_price,
                  high_price, low_price, volume, amount, turnover_rate, source_name, observed_at
                ) VALUES
                  ('old', '600000', DATE '2026-08-28', 'DAY_QFQ', 9.8, 10.0, 10.2, 9.7,
                   1000, 10000, 1.2, 'test', TIMESTAMPTZ '2026-08-28 07:00:00+00'),
                  ('new', '600000', DATE '2026-08-28', 'DAY_QFQ', 9.8, 10.5, 10.6, 9.7,
                   1200, 12000, 1.4, 'test', TIMESTAMPTZ '2026-08-28 07:01:00+00')
                """);

        bars.importLatestHistoryVersions();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM first_board_daily_bar", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT close_price FROM first_board_daily_bar", BigDecimal.class))
                .isEqualByComparingTo("10.500000");
        assertThat(jdbc.queryForObject(
                "SELECT content_hash FROM first_board_daily_bar", String.class)).isEqualTo("new");
    }

    @Test
    void upsertIsIdempotentAndReplacesChangedBar() {
        LocalDate date = LocalDate.of(2026, 8, 28);
        EastMoneyKLine original = bar("600000", date, "10.00", "10.20");
        bars.upsertBars(List.of(original), "test");
        bars.upsertBars(List.of(original), "test");
        bars.upsertBars(List.of(bar("600000", date, "10.20", "10.30")), "test");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM first_board_daily_bar", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT close_price FROM first_board_daily_bar", BigDecimal.class))
                .isEqualByComparingTo("10.200000");
    }

    @Test
    void publishAndExactLookupExposeOnlyReadyCompleteRun() {
        LocalDate baselineDate = LocalDate.of(2026, 8, 28);
        bars.upsertBars(java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> bar(
                        "600000", baselineDate.minusDays(19L - index), "10.00", "10.20"))
                .toList(), "test");
        FirstBoardBaselineRun run = baselines.startRun(
                LocalDate.of(2026, 8, 31), baselineDate,
                "first-board-baseline-v1", "bars-a", 2, 2, 0, 0);

        FirstBoardBaselineSnapshot published = baselines.publish(run, List.of(
                baseline("600000", "浦发银行"), baseline("000001", "平安银行")));

        assertThat(published.runId()).isEqualTo(run.runId());
        assertThat(published.stocks()).containsOnlyKeys("600000", "000001");
        assertThat(published.stocks().get("600000").recentBars()).hasSize(20);
        assertThat(baselines.findReady(
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 28),
                "first-board-baseline-v1", "bars-a")).isPresent();
        assertThat(baselines.findReady(
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 28),
                "first-board-baseline-v1", "bars-b")).isEmpty();
    }

    private EastMoneyKLine bar(String symbol, LocalDate date, String close, String high) {
        return new EastMoneyKLine(symbol, date, new BigDecimal("9.80"), new BigDecimal(close),
                new BigDecimal(high), new BigDecimal("9.70"), new BigDecimal("1000"),
                new BigDecimal("10000"), new BigDecimal("1.20"));
    }

    private FirstBoardBaseline baseline(String symbol, String name) {
        return new FirstBoardBaseline(
                symbol, name, "银行", FirstBoardBoard.MAIN, LocalDate.of(2026, 8, 28),
                new BigDecimal("10"), new BigDecimal("9.8"), new BigDecimal("9.5"),
                new BigDecimal("1.2"), new BigDecimal("55"), new BigDecimal("0.2"), 5,
                new BigDecimal("1.3"), new BigDecimal("1.5"), new BigDecimal("2.1"),
                new BigDecimal("1.0"), false, List.of());
    }
}
