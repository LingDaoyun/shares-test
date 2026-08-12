package com.aistock.research.shortterm.validation;

import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermMarketRegime;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermSignalProfile;
import com.aistock.research.shortterm.ShortTermTechnicalSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermObservationServiceTest {

    private final ShortTermObservationWriter writer = mock(ShortTermObservationWriter.class);
    private final TradingClockService tradingClock = mock(TradingClockService.class);
    private final ShortTermValidationSettings settings = mock(ShortTermValidationSettings.class);
    private ShortTermObservationService service;

    @BeforeEach
    void setUp() {
        when(settings.costAssumptions()).thenReturn(new ShortTermValidationCostAssumptions(
                decimal("0.03"), decimal("0.03"), decimal("0.05"), decimal("0.05"), decimal("0.05")));
        when(tradingClock.verifiedTradingDayAfter(LocalDate.parse("2026-08-12"), 1))
                .thenReturn(Optional.of(LocalDate.parse("2026-08-13")));
        when(tradingClock.verifiedTradingDayAfter(LocalDate.parse("2026-08-12"), 2))
                .thenReturn(Optional.of(LocalDate.parse("2026-08-14")));
        service = new ShortTermObservationService(
                writer, tradingClock, settings, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void capturesOneScheduledCandidateIdempotentlyWithTwoHorizonSlots() {
        ShortTermReport report = report();
        when(writer.exists(any())).thenReturn(false, true);
        when(writer.persistIfAbsent(any(), any())).thenReturn(true);

        int first = service.captureScheduledFinal(
                "scheduled:2026-08-12:final", report, Instant.parse("2026-08-12T06:49:20Z"));
        int second = service.captureScheduledFinal(
                "scheduled:2026-08-12:final", report, Instant.parse("2026-08-12T06:49:30Z"));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        ArgumentCaptor<ShortTermSignalObservationEntity> observationCaptor =
                ArgumentCaptor.forClass(ShortTermSignalObservationEntity.class);
        ArgumentCaptor<List<ShortTermSignalOutcomeEntity>> outcomeCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).persistIfAbsent(observationCaptor.capture(), outcomeCaptor.capture());
        assertThat(observationCaptor.getValue().isCalibrationEligible()).isTrue();
        assertThat(observationCaptor.getValue().getSignalFamily()).isEqualTo("GOLDEN_CROSS_VOLUME");
        assertThat(outcomeCaptor.getValue())
                .extracting(ShortTermSignalOutcomeEntity::getHorizon)
                .containsExactly("T1", "T2");
    }

    @Test
    void manualScanIsStoredButExcludedFromCalibratedProductionCohorts() {
        when(writer.exists(any())).thenReturn(false);
        when(writer.persistIfAbsent(any(), any())).thenReturn(true);

        service.captureManual(
                "manual-job-1",
                report(),
                ShortTermSnapshotStatus.FINAL_READY,
                List.of(),
                Instant.parse("2026-08-12T06:49:20Z")
        );

        ArgumentCaptor<ShortTermSignalObservationEntity> captor =
                ArgumentCaptor.forClass(ShortTermSignalObservationEntity.class);
        verify(writer).persistIfAbsent(captor.capture(), any());
        assertThat(captor.getValue().isValidationEligible()).isTrue();
        assertThat(captor.getValue().isCalibrationEligible()).isFalse();
        assertThat(captor.getValue().getPublicationType()).isEqualTo("MANUAL_SCAN");
    }

    @Test
    void blockedReportPersistsCandidateButDoesNotCreateOutcomeSlots() {
        when(writer.exists(any())).thenReturn(false);
        when(writer.persistIfAbsent(any(), any())).thenReturn(true);

        service.captureManual(
                "manual-job-blocked",
                report(),
                ShortTermSnapshotStatus.DATA_BLOCKED,
                List.of("COVERAGE_BELOW_95"),
                Instant.parse("2026-08-12T06:49:20Z")
        );

        ArgumentCaptor<ShortTermSignalObservationEntity> captor =
                ArgumentCaptor.forClass(ShortTermSignalObservationEntity.class);
        ArgumentCaptor<List<ShortTermSignalOutcomeEntity>> outcomes = ArgumentCaptor.forClass(List.class);
        verify(writer).persistIfAbsent(captor.capture(), outcomes.capture());
        assertThat(captor.getValue().isValidationEligible()).isFalse();
        assertThat(captor.getValue().getValidationBlockReason()).contains("COVERAGE_BELOW_95");
        assertThat(outcomes.getValue()).isEmpty();
    }

    @Test
    void staleCandidateTradeDateIsPersistedButNeverBecomesValidationEligible() {
        ShortTermReport report = report(LocalDate.parse("2026-08-11"));
        when(writer.exists(any())).thenReturn(false);
        when(writer.persistIfAbsent(any(), any())).thenReturn(true);
        when(tradingClock.verifiedTradingDayAfter(LocalDate.parse("2026-08-11"), 1))
                .thenReturn(Optional.of(LocalDate.parse("2026-08-12")));
        when(tradingClock.verifiedTradingDayAfter(LocalDate.parse("2026-08-11"), 2))
                .thenReturn(Optional.of(LocalDate.parse("2026-08-13")));

        service.captureScheduledFinal(
                "scheduled:stale", report, Instant.parse("2026-08-12T06:49:20Z"));

        ArgumentCaptor<ShortTermSignalObservationEntity> captor =
                ArgumentCaptor.forClass(ShortTermSignalObservationEntity.class);
        ArgumentCaptor<List<ShortTermSignalOutcomeEntity>> outcomes = ArgumentCaptor.forClass(List.class);
        verify(writer).persistIfAbsent(captor.capture(), outcomes.capture());
        assertThat(captor.getValue().isValidationEligible()).isFalse();
        assertThat(captor.getValue().getValidationBlockReason())
                .contains("QUOTE_TRADE_DATE_MISMATCH");
        assertThat(outcomes.getValue()).isEmpty();
    }

    @Test
    void concurrentDatabaseWinnerIsTreatedAsIdempotentSuccess() {
        when(writer.exists(any())).thenReturn(false, true);
        when(writer.persistIfAbsent(any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate publication"));

        int created = service.captureScheduledFinal(
                "scheduled:race", report(), Instant.parse("2026-08-12T06:49:20Z"));

        assertThat(created).isZero();
    }

    private ShortTermReport report() {
        return report(LocalDate.parse("2026-08-12"));
    }

    private ShortTermReport report(LocalDate quoteTradeDate) {
        ShortTermReport report = mock(ShortTermReport.class);
        ShortTermCandidate candidate = mock(ShortTermCandidate.class);
        ShortTermTechnicalSnapshot technical = mock(ShortTermTechnicalSnapshot.class);
        TradingAdvice advice = mock(TradingAdvice.class);
        when(candidate.symbol()).thenReturn("600001");
        when(candidate.name()).thenReturn("样本公司");
        when(candidate.rank()).thenReturn(1);
        when(candidate.latestPrice()).thenReturn(decimal("100"));
        when(candidate.quoteFreshness()).thenReturn(new QuoteFreshnessSnapshot(
                "REALTIME",
                "实时",
                true,
                false,
                quoteTradeDate,
                quoteTradeDate.atTime(14, 49)
                        .atZone(TradingClockService.CHINA_MARKET_ZONE)
                        .toInstant(),
                0L,
                "测试行情"
        ));
        when(candidate.todayAdvice()).thenReturn(advice);
        when(advice.action()).thenReturn("WAIT");
        when(candidate.technical()).thenReturn(technical);
        when(technical.tradeDate()).thenReturn(quoteTradeDate);
        when(candidate.signalProfile()).thenReturn(new ShortTermSignalProfile(
                "GOLDEN_CROSS_VOLUME", "金叉量价", List.of("GOLDEN_CROSS_VOLUME"), List.of(), List.of()));
        when(report.candidates()).thenReturn(List.of(candidate));
        when(report.dataCutoffAt()).thenReturn(Instant.parse("2026-08-12T06:49:00Z"));
        when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
                5885, 5885, 0, BigDecimal.ONE, true, "EAST_MONEY", Instant.parse("2026-08-12T06:48:50Z")));
        when(report.marketRegime()).thenReturn(new ShortTermMarketRegime(
                "TREND_EXPANSION", "有序趋势扩张", decimal("65"), decimal("0.8"), decimal("1.2"),
                decimal("60"), decimal("1"), decimal("0.1"), 5885, "NORMAL", "test", List.of()));
        return report;
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
