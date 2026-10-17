package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aistock.research.fiveday.FiveDayModels.*;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static com.aistock.research.fiveday.FiveDayOutcomeCalculatorTest.candidate;
import static com.aistock.research.fiveday.FiveDayOutcomeCalculatorTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FiveDayHistoryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final FirstBoardDailyBarStore bars = mock(FirstBoardDailyBarStore.class);

    @Test
    void completionAfterEntryAuctionCutoffCannotRemainForwardEligible() throws Exception {
        FiveDayHistoryService service = service("2026-09-08T01:16:00Z");
        service.capture(snapshot(List.of(candidate("000001", true, true)), true, false, false));
        Snapshot stored = mapper.readValue(jdbc.snapshotJson, Snapshot.class);
        assertThat(stored.forwardEligible()).isFalse();
        assertThat(stored.dataGaps()).anyMatch(message -> message.contains("09:15"));
        verifyNoInteractions(bars);
    }

    @Test
    void insertCompletingAtCutoffIsNotAForwardObservation() throws Exception {
        TradingClockService clock = spy(new TradingClockService(Clock.fixed(
                Instant.parse("2026-09-08T01:14:59Z"), ZoneId.of("Asia/Shanghai"))));
        when(clock.currentMarketDateTime()).thenReturn(
                LocalDateTime.parse("2026-09-08T09:14:59"), LocalDateTime.parse("2026-09-08T09:15:00"));
        new FiveDayHistoryService(jdbc, mapper, bars, clock)
                .capture(snapshot(List.of(candidate("000001", true, true)), true, false, false));
        Snapshot stored = mapper.readValue(jdbc.snapshotJson, Snapshot.class);
        assertThat(stored.forwardEligible()).isFalse();
        assertThat(jdbc.capturedAt.toInstant()).isEqualTo(Instant.parse("2026-09-08T01:15:00Z"));
    }

    @Test
    void firstInsertIsConservativeUntilItsCompletionIsKnown() throws Exception {
        service("2026-09-07T09:00:00Z")
                .capture(snapshot(List.of(candidate("000001", true, true)), true, false, false));
        assertThat(mapper.readValue(jdbc.insertedSnapshotJson, Snapshot.class).forwardEligible()).isFalse();
        assertThat(mapper.readValue(jdbc.snapshotJson, Snapshot.class).forwardEligible()).isTrue();
    }

    @Test
    void aDuplicateCaptureNeverReclassifiesTheFirstSnapshot() {
        jdbc.inserted = false;
        service("2026-09-07T09:00:00Z")
                .capture(snapshot(List.of(candidate("000001", true, true)), true, false, false));
        assertThat(jdbc.updates).isEqualTo(1);
    }

    @Test
    void unfinishedScansAreNotPersistedAsCompleteObservations() {
        Snapshot ready = snapshot(List.of(), true, false, false);
        Snapshot partial = new Snapshot("PARTIAL", ready.ruleVersion(), ready.signalDate(), ready.generatedAt(),
                true, false, false, "缺覆盖", ready.rules(), ready.coverage(), ready.candidates(), List.of("缺覆盖"));
        service("2026-09-07T09:00:00Z").capture(partial);
        assertThat(jdbc.lastArguments).isNull();
    }

    @Test
    void v2ContextSurvivesBothPendingAndFinalFreeze() throws Exception {
        Snapshot snapshot = v2(snapshot(List.of(candidate("600001", true, true)), true, false, false),
                List.of(assessment("600001", "WATCH")));

        service("2026-09-07T09:00:00Z").capture(snapshot);

        assertThat(jdbc.insertedSnapshotJson).isNotNull();
        Snapshot pending = mapper.readValue(jdbc.insertedSnapshotJson, Snapshot.class);
        Snapshot frozen = mapper.readValue(jdbc.snapshotJson, Snapshot.class);
        assertThat(pending.forwardEligible()).isFalse();
        assertThat(frozen.forwardEligible()).isTrue();
        assertThat(pending.context()).isEqualTo(snapshot.context());
        assertThat(frozen.context()).isEqualTo(snapshot.context());
    }

    @Test
    void v2WithoutContextCannotBeCaptured() {
        Snapshot base = snapshot(List.of(), true, false, false);
        Snapshot missing = new Snapshot(base.status(), VERSION_V2, base.signalDate(), base.generatedAt(),
                true, false, false, "缺少上下文", base.rules(), base.coverage(), base.candidates(), List.of(), null);

        service("2026-09-07T09:00:00Z").capture(missing);

        assertThat(jdbc.lastArguments).isNull();
    }

    @Test
    void v2CohortsUseFrozenDecisionsAndRetainNonExecutableControlObservations() throws Exception {
        Snapshot saved = v2(snapshot(List.of(candidate("600001", true, true),
                        candidate("600002", true, false), candidate("600003", false, true),
                        candidate("600004", true, true)), true, false, false),
                List.of(assessment("600001", "WATCH"), assessment("600002", "ELIGIBLE"),
                        assessment("600003", "ELIGIBLE")));
        jdbc.rows.add(row("v2", saved, Instant.parse("2026-09-07T08:01:00Z"),
                Map.of("600001", completed("1"), "600002", completed("2"), "600003", completed("90"))));

        History history = service("2026-09-15T08:00:00Z").history(VERSION_V2);

        assertThat(jdbc.lastQueryArguments).containsExactly(VERSION_V2);
        assertThat(history.rows()).extracting(HistoryRow::decision).containsExactly("WATCH", "ELIGIBLE", "ELIGIBLE", "UNKNOWN");
        assertThat(history.cohorts().get(0).label()).isEqualTo("主线量价对照（资金完整）");
        assertThat(history.cohorts().get(0).samples()).isEqualTo(3);
        assertThat(history.cohorts().get(1).label()).isEqualTo("执行条件齐备（对照子集）");
        assertThat(history.cohorts().get(1).samples()).isEqualTo(1);
        assertThat(history.cohorts().get(1).meanFixedNetReturnPercent()).isEqualByComparingTo("2");
        assertThat(history.notes()).anyMatch(note -> note.contains("不可执行观察") && note.contains("真实成交"));
        verifyNoInteractions(bars);
    }

    @Test
    void explicitVersionFiltersEveryReadAndLegacyOverloadsKeepV1() {
        FiveDayHistoryService service = service("2026-09-15T08:00:00Z");
        LocalDate date = LocalDate.parse("2026-09-07");
        service.latest(date, true, false, VERSION_V2);
        assertThat(jdbc.lastQueryArguments).containsExactly(java.sql.Date.valueOf(date), VERSION_V2, true, false);
        service.latest(date, true, false);
        assertThat(jdbc.lastQueryArguments).containsExactly(java.sql.Date.valueOf(date), VERSION, true, false);
        service.refresh(VERSION_V2);
        assertThat(jdbc.lastQueryArguments).containsExactly(VERSION_V2);
        service.refresh();
        assertThat(jdbc.lastQueryArguments).containsExactly(VERSION);
        service.history();
        assertThat(jdbc.lastQueryArguments).containsExactly(VERSION);
    }

    @Test
    void cohortsDeduplicateScopesAndExcludeMissingFlowAndLateScans() throws Exception {
        Candidate a = candidate("000001", true, true);
        Candidate missing = candidate("000002", false, false);
        jdbc.rows.add(row("first", snapshot(List.of(a, missing), true, false, false),
                Instant.parse("2026-09-07T08:01:00Z"), Map.of("000001", completed("1"), "000002", completed("90"))));
        jdbc.rows.add(row("scope-copy", snapshot(List.of(a), true, true, false),
                Instant.parse("2026-09-07T08:02:00Z"), Map.of("000001", completed("9"))));
        jdbc.rows.add(row("late", snapshot(List.of(candidate("000003", true, false)), false, false, true),
                Instant.parse("2026-09-08T03:00:00Z"), Map.of("000003", completed("70"))));

        History history = service("2026-09-15T08:00:00Z").history();
        assertThat(history.cohorts()).hasSize(2);
        assertThat(history.cohorts().get(0).samples()).isEqualTo(1);
        assertThat(history.cohorts().get(0).meanFixedNetReturnPercent()).isEqualByComparingTo("1");
        assertThat(history.cohorts().get(1).samples()).isEqualTo(1);
        assertThat(history.notes()).anyMatch(note -> note.contains("30"));
        verifyNoInteractions(bars);
    }

    @Test
    void refreshBefore1540UsesThePreviousMarketDay() throws Exception {
        jdbc.rows.add(row("first", snapshot(List.of(candidate("000001", true, true)), true, false, false),
                Instant.parse("2026-09-07T08:01:00Z"), Map.of()));
        when(bars.recentBars(anyString(), any(LocalDate.class), eq(180))).thenReturn(List.of());
        service("2026-09-15T07:39:59Z").refresh();
        verify(bars).recentBars("000001", LocalDate.parse("2026-09-14"), 180);
    }

    @Test
    void refreshAt1540MayUseTheCurrentMarketDay() throws Exception {
        jdbc.rows.add(row("first", snapshot(List.of(candidate("000001", true, true)), true, false, false),
                Instant.parse("2026-09-07T08:01:00Z"), Map.of()));
        when(bars.recentBars(anyString(), any(LocalDate.class), eq(180))).thenReturn(List.of());
        service("2026-09-15T07:40:00Z").refresh();
        verify(bars).recentBars("000001", LocalDate.parse("2026-09-15"), 180);
    }

    @Test
    void notesDiscloseRiskCompletionCountsSeparatelyFromFixedCompletion() throws Exception {
        Outcome fixedOnly = new Outcome("COMPLETED", "MISSING_DATA", LocalDate.parse("2026-09-08"), BigDecimal.TEN,
                LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"), BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ONE, null, null, null, null, List.of("风险路径缺数据"));
        jdbc.rows.add(row("first", snapshot(List.of(candidate("000001", true, true),
                        candidate("000002", true, false)), true, false, false),
                Instant.parse("2026-09-07T08:01:00Z"), Map.of("000001", fixedOnly, "000002", completed("2"))));
        History history = service("2026-09-15T08:00:00Z").history();
        assertThat(history.cohorts().get(0).completed()).isEqualTo(2);
        assertThat(history.cohorts().get(0).meanRiskNetReturnPercent()).isEqualByComparingTo("2");
        assertThat(history.notes()).anyMatch(note -> note.contains("量价对照（资金完整）") && note.contains("风险模型完成1个"));
        assertThat(history.notes()).anyMatch(note -> note.contains("资金增强（对照的子集）") && note.contains("风险模型完成0个"));
    }

    private FiveDayHistoryService service(String now) {
        return new FiveDayHistoryService(jdbc, mapper, bars, new TradingClockService(
                Clock.fixed(Instant.parse(now), ZoneId.of("Asia/Shanghai"))));
    }

    private Snapshot v2(Snapshot base, List<CandidateAssessment> assessments) {
        Context context = new Context(base.generatedAt(), null, List.of(), assessments, List.of(), "READY", List.of());
        return new Snapshot(base.status(), VERSION_V2, base.signalDate(), base.generatedAt(), base.forwardEligible(),
                base.includeGrowth(), base.includeStar(), base.message(), base.rules(), base.coverage(),
                base.candidates(), base.dataGaps(), context);
    }

    private CandidateAssessment assessment(String symbol, String decision) {
        return new CandidateAssessment(symbol, "半导体", decision, null, null, List.of(), List.of());
    }

    private ResultSet row(String id, Snapshot snapshot, Instant capturedAt, Map<String, Outcome> outcomes) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("scan_id")).thenReturn(id);
        when(result.getString("snapshot_json")).thenReturn(mapper.writeValueAsString(snapshot));
        when(result.getString("outcomes_json")).thenReturn(mapper.writeValueAsString(outcomes));
        when(result.getTimestamp("captured_at")).thenReturn(Timestamp.from(capturedAt));
        return result;
    }

    private Outcome completed(String net) {
        return new Outcome("COMPLETED", "COMPLETED", LocalDate.parse("2026-09-08"), BigDecimal.TEN,
                LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"), BigDecimal.TEN,
                BigDecimal.ZERO, new BigDecimal(net), LocalDate.parse("2026-09-14"), BigDecimal.TEN,
                new BigDecimal(net), BigDecimal.ZERO, List.of("日线情景"));
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private Object[] lastArguments;
        private Object[] lastQueryArguments;
        private String snapshotJson;
        private String insertedSnapshotJson;
        private Timestamp capturedAt;
        private boolean inserted = true;
        private int updates;
        private final List<ResultSet> rows = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            lastArguments = args;
            updates++;
            if (sql.contains("INSERT INTO five_day_scan")) {
                snapshotJson = (String) args[6];
                insertedSnapshotJson = snapshotJson;
                capturedAt = (Timestamp) args[8];
                return inserted ? 1 : 0;
            }
            if (sql.contains("SET forward_eligible")) {
                snapshotJson = (String) args[1];
                capturedAt = (Timestamp) args[2];
            }
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            try {
                List<T> result = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) result.add(rowMapper.mapRow(rows.get(i), i));
                return result;
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            lastQueryArguments = args;
            return query(sql, rowMapper);
        }
    }
}
