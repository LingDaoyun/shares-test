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

        assertThat(gate.evaluateManual(report(
                Instant.parse("2026-07-23T06:51:00Z"), true, new BigDecimal("0.99"), true,
                List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))),
                DECISION_AT).status()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
    }

    @Test
    void blocksManualReportOutsideTailDecisionWindow() {
        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:39:00Z"), false,
                        new BigDecimal("0.99"), true, List.of()),
                Instant.parse("2026-07-23T06:40:00Z"));

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("MANUAL_OUTSIDE_DECISION_WINDOW");
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
    void blocksManualReportAt1457Boundary() {
        Instant decisionAt = Instant.parse("2026-07-23T06:57:00Z");

        ShortTermFinalResultGate.Result result = gate.evaluateManual(
                report(
                        Instant.parse("2026-07-23T06:56:00Z"), false,
                        new BigDecimal("0.99"), true, List.of()),
                decisionAt);

        assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(result.blockedReasons()).containsExactly("MANUAL_OUTSIDE_DECISION_WINDOW");
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
