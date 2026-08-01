package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.TradingSessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortTermManualResultGateTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-07-23");
    private static final Instant DECISION_AT = Instant.parse("2026-07-23T06:52:00Z");

    private ShortTermFinalResultGate gate;

    @BeforeEach
    void setUp() {
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        when(settings.finalDeadline()).thenReturn(LocalTime.parse("14:53:59"));
        when(settings.freshness()).thenReturn(Duration.ofMinutes(3));
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(TRADE_DATE);
        when(tradingClock.isMarketClosedDay(TRADE_DATE)).thenReturn(false);
        gate = new ShortTermFinalResultGate(settings, tradingClock);
    }

    @Test
    void classifiesReliableManualReportAuthoritatively() {
        assertThat(gate.evaluateManual(report(
                Instant.parse("2026-07-23T06:51:00Z"), true, new BigDecimal("0.99"), true, List.of()),
                DECISION_AT).status()).isEqualTo(ShortTermSnapshotStatus.NO_TRADE);

        ShortTermFinalResultGate.Result finalReady = gate.evaluateManual(report(
                Instant.parse("2026-07-23T06:51:00Z"), true, new BigDecimal("0.99"), true,
                List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))),
                DECISION_AT);

        assertThat(finalReady.status()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
        assertThat(finalReady.message()).isEqualTo("手动分析已完成，已生成当前时点候选");
    }

    @Test
    void allowsManualReportOutsideTailDecisionWindow() {
        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:39:00Z"), false,
                        new BigDecimal("0.99"), true, List.of()),
                Instant.parse("2026-07-23T06:40:00Z"));

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.NO_TRADE);
        assertThat(result.message()).isEqualTo("手动分析已完成，当前无合格候选");
        assertThat(result.blockedReasons()).isEmpty();
    }

    @Test
    void allowsManualReportAt1455EvenAfterScheduledDeadline() {
        Instant decisionAt = Instant.parse("2026-07-23T06:55:00Z");

        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:54:00Z"), true,
                        new BigDecimal("0.99"), true, List.of()),
                decisionAt);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.NO_TRADE);
    }

    @Test
    void allowsManualReportAfterMarketCloseAndReturnsCandidates() {
        Instant decisionAt = Instant.parse("2026-07-23T07:51:35Z");

        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T07:51:07Z"), false,
                        new BigDecimal("1.00"), true,
                        List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))),
                decisionAt);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
        assertThat(result.message()).isEqualTo("手动分析已完成，已生成当前时点候选");
        assertThat(result.blockedReasons()).isEmpty();
    }

    @Test
    void allowsScheduledResultAtExactFinalDeadline() {
        Instant decisionAt = Instant.parse("2026-07-23T06:53:59Z");

        ShortTermFinalResultGate.Result result = gate.evaluateScheduled(
                TRADE_DATE,
                report(
                        Instant.parse("2026-07-23T06:53:00Z"), true,
                        new BigDecimal("0.99"), true, List.of()),
                decisionAt,
                decisionAt
        );

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.NO_TRADE);
    }

    @Test
    void blocksScheduledResultOneSecondAfterFinalDeadline() {
        Instant decisionAt = Instant.parse("2026-07-23T06:54:00Z");

        ShortTermFinalResultGate.Result result = gate.evaluateScheduled(
                TRADE_DATE,
                report(
                        Instant.parse("2026-07-23T06:53:00Z"), true,
                        new BigDecimal("0.99"), true, List.of()),
                decisionAt,
                decisionAt
        );

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("FINAL_DEADLINE_EXPIRED");
    }

    @Test
    void blocksWrongTradeDate() {
        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-22T06:51:00Z"), true,
                        new BigDecimal("0.99"), true, List.of()),
                DECISION_AT);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("CUTOFF_WRONG_DATE");
    }

    @Test
    void blocksUnreliableCoverage() {
        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:51:00Z"), true,
                        new BigDecimal("0.99"), false, List.of()),
                DECISION_AT);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("COVERAGE_BELOW_90");
    }

    @Test
    void blocksStaleQuotes() {
        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:45:00Z"), true,
                        new BigDecimal("0.99"), true, List.of()),
                DECISION_AT);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("QUOTE_STALE");
    }

    @Test
    void allowsFreshlyFetchedAfterHoursSnapshotWhenMarketTimestampHasStopped() {
        Instant cutoff = Instant.parse("2026-07-23T08:12:02Z");
        Instant fetchedAt = Instant.parse("2026-07-23T10:03:17Z");
        Instant completedAt = Instant.parse("2026-07-23T10:03:44Z");
        ShortTermReport report = report(
                cutoff,
                false,
                new BigDecimal("1.00"),
                true,
                List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))
        );
        when(report.tradingSession()).thenReturn(new TradingSessionSnapshot(
                "CLOSED",
                "非交易时段",
                false,
                false,
                false,
                "休市",
                List.of("非交易时段只更新研究和复盘，不给盘中执行信号。"),
                List.of("休市行情不能作为当日买点。")
        ));
        when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
                5884,
                5884,
                0,
                new BigDecimal("1.00"),
                true,
                "东方财富行情",
                fetchedAt
        ));

        ShortTermFinalResultGate.Result result = gate.evaluateManual(report, completedAt);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
        assertThat(result.message()).isEqualTo("手动分析已完成，已生成当前时点候选");
        assertThat(result.blockedReasons()).isEmpty();
    }

    @Test
    void allowsManualCachePreviewOnClosedMarketDayWithoutCallingItExecutable() {
        LocalDate closedDate = LocalDate.parse("2026-08-01");
        Instant priorTradeCutoff = Instant.parse("2026-07-31T07:00:00Z");
        Instant fetchedAt = Instant.parse("2026-08-01T02:10:00Z");
        Instant completedAt = Instant.parse("2026-08-01T02:10:20Z");

        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        when(settings.finalDeadline()).thenReturn(LocalTime.parse("14:53:59"));
        when(settings.freshness()).thenReturn(Duration.ofMinutes(3));
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(closedDate);
        when(tradingClock.isMarketClosedDay(closedDate)).thenReturn(true);
        ShortTermFinalResultGate closedDayGate = new ShortTermFinalResultGate(settings, tradingClock);

        ShortTermReport report = report(
                priorTradeCutoff,
                false,
                new BigDecimal("1.00"),
                true,
                List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))
        );
        when(report.tradingSession()).thenReturn(new TradingSessionSnapshot(
                "CLOSED",
                "休市",
                false,
                false,
                true,
                "休市",
                List.of("休市行情只用于策略预览。"),
                List.of("缓存行情不能作为当日买点。")
        ));
        when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
                5884,
                5884,
                0,
                new BigDecimal("1.00"),
                true,
                "东方财富缓存行情",
                fetchedAt
        ));

        ShortTermFinalResultGate.Result result = closedDayGate.evaluateManual(report, completedAt);

        assertThat(result.status().name()).isEqualTo("CACHE_PREVIEW");
        assertThat(result.message()).isEqualTo("缓存行情预览已完成，已生成策略候选；休市数据不作为今日买点");
        assertThat(result.blockedReasons()).containsExactly("STATIC_CACHE_PREVIEW");
    }

    private ShortTermReport report(
            Instant cutoff,
            boolean closingDecisionWindow,
            BigDecimal coverageRatio,
            boolean executionReliable,
            List<com.aistock.research.shortterm.ShortTermCandidate> candidates
    ) {
        ShortTermReport report = mock(ShortTermReport.class);
        when(report.tradingSession()).thenReturn(new TradingSessionSnapshot(
                "AFTERNOON_CONTINUOUS",
                "下午连续竞价",
                true,
                closingDecisionWindow,
                false,
                "14:45-14:56",
                List.of(),
                List.of()
        ));
        when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
                5500,
                5450,
                50,
                coverageRatio,
                executionReliable,
                "SINA",
                cutoff
        ));
        when(report.dataCutoffAt()).thenReturn(cutoff);
        when(report.candidates()).thenReturn(candidates);
        return report;
    }
}
