package com.aistock.research.tradefeedback;

import com.aistock.research.cycle.CycleTrialController;
import com.aistock.research.cycle.CycleTrialReport;
import com.aistock.research.cycle.CycleTrialService;
import com.aistock.research.dailysignal.DailySignalController;
import com.aistock.research.dailysignal.DailySignalReport;
import com.aistock.research.dailysignal.DailySignalService;
import com.aistock.research.market.MarketScanController;
import com.aistock.research.market.MarketScanReport;
import com.aistock.research.market.MarketScanService;
import com.aistock.research.mispricing.MispricingController;
import com.aistock.research.mispricing.MispricingReport;
import com.aistock.research.mispricing.MispricingService;
import com.aistock.research.shortterm.ShortTermController;
import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanJobService;
import com.aistock.research.shortterm.ShortTermScanJobStatus;
import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotStore;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStage;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tech.TechTrackingController;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.tech.TechTrackingService;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationControllerAttestationTest {

    private final RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);

    @Test
    void shortTermReportAndCompletedScanResponsesAreAttested() {
        ShortTermService service = mock(ShortTermService.class);
        ShortTermScanJobService jobs = mock(ShortTermScanJobService.class);
        ShortTermReport report = mock(ShortTermReport.class);
        ShortTermScanJobStatus status = mock(ShortTermScanJobStatus.class);
        when(service.report(null, null, null, null, null, null, null, null, null, null, null)).thenReturn(report);
        when(jobs.get("job-1")).thenReturn(status);
        when(attestations.attest(report)).thenReturn(report);
        when(attestations.attest(status)).thenReturn(status);
        ShortTermController controller = new ShortTermController(
                service,
                jobs,
                attestations,
                mock(TradingClockService.class),
                mock(ShortTermScheduledSnapshotStore.class),
                mock(ShortTermAutomationSettings.class)
        );

        assertThat(controller.report(null, null, null, null, null, null, null, null, null, null, null)).isSameAs(report);
        assertThat(controller.scanJob("job-1")).isSameAs(status);

        verify(attestations).attest(report);
        verify(attestations).attest(status);
    }

    @Test
    void preparedSnapshotReceivesConsumableAttestationWithoutPersistingToken() {
        Instant now = Instant.parse("2026-07-23T06:53:00Z");
        LocalDate tradeDate = LocalDate.parse("2026-07-23");
        RecommendationAttestationService service = new RecommendationAttestationService(
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                100
        );
        ShortTermReport report = attestableReport(
                now, tradeDate, Map.of("600000", "persisted-token-must-not-be-used"));
        ShortTermScheduledSnapshot stored = new ShortTermScheduledSnapshot(
                "snapshot-1", tradeDate, ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_READY, 1, "fingerprint", "{}",
                now.minusSeconds(60), now.minusSeconds(120), now,
                "尾盘最终结果已就绪", List.of(), report
        );
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(tradeDate);
        when(store.latest(tradeDate)).thenReturn(Optional.of(stored));
        ShortTermController controller = new ShortTermController(
                mock(ShortTermService.class),
                mock(ShortTermScanJobService.class),
                service,
                tradingClock,
                store,
                mock(ShortTermAutomationSettings.class)
        );

        ShortTermScheduledSnapshot response = controller.latestScheduledSnapshot();
        String token = response.report().tradeCaptureTokens().get("600000");

        assertThat(token).isNotBlank().isNotEqualTo("persisted-token-must-not-be-used");
        assertThat(service.require(token).symbol()).isEqualTo("600000");
        assertThat(stored.report().tradeCaptureTokens())
                .containsEntry("600000", "persisted-token-must-not-be-used");
    }

    @Test
    void dataBlockedManualResponseDoesNotIssueConsumableAttestations() {
        Instant now = Instant.parse("2026-07-23T06:53:00Z");
        RecommendationAttestationService service = new RecommendationAttestationService(
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                100
        );
        ShortTermReport report = attestableReport(
                now, LocalDate.parse("2026-07-23"), Map.of());
        ShortTermScanJobStatus blocked = new ShortTermScanJobStatus(
                "job-blocked", "SUCCEEDED", LocalDate.parse("2026-07-23"),
                ShortTermSnapshotStatus.DATA_BLOCKED, List.of("QUOTE_STALE"),
                now.minusSeconds(30), now.minusSeconds(30), now,
                "尾盘行情已经过期", report
        );

        ShortTermScanJobStatus response = service.attest(blocked);

        assertThat(response.report().tradeCaptureTokens()).isEmpty();
    }

    @Test
    void hotTrackerResponseIsAttested() {
        TechTrackingService service = mock(TechTrackingService.class);
        TechTrackingReport report = mock(TechTrackingReport.class);
        when(service.report(null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        TechTrackingReport result = new TechTrackingController(service, attestations)
                .report(null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void mispricingResponseIsAttested() {
        MispricingService service = mock(MispricingService.class);
        MispricingReport report = mock(MispricingReport.class);
        when(service.report(null, null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        MispricingReport result = new MispricingController(service, attestations)
                .report(null, null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void cycleResponseIsAttested() {
        CycleTrialService service = mock(CycleTrialService.class);
        CycleTrialReport report = mock(CycleTrialReport.class);
        when(service.report(null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        CycleTrialReport result = new CycleTrialController(service, attestations)
                .report(null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void dailySignalResponseIsAttested() {
        DailySignalService service = mock(DailySignalService.class);
        DailySignalReport report = mock(DailySignalReport.class);
        when(service.report(null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        DailySignalReport result = new DailySignalController(service, attestations)
                .report(null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void marketScanResponseIsAttestedForLongTermBuyEntry() {
        MarketScanService service = mock(MarketScanService.class);
        MarketScanReport raw = mock(MarketScanReport.class);
        MarketScanReport attested = mock(MarketScanReport.class);
        when(service.report(null, null, null, null, null, null, null, null, null, null)).thenReturn(raw);
        when(attestations.attest(raw)).thenReturn(attested);

        MarketScanReport result = new MarketScanController(service, attestations)
                .report(null, null, null, null, null, null, null, null, null, null);

        assertThat(result).isSameAs(attested);
        verify(attestations).attest(raw);
    }

    private ShortTermReport attestableReport(
            Instant now,
            LocalDate tradeDate,
            Map<String, String> persistedTokens
    ) {
        ShortTermCandidate candidate = new ShortTermCandidate(
                1, "600000", "浦发银行", "沪市", "银行",
                new BigDecimal("10.20"), BigDecimal.ONE, null, null, new BigDecimal("1000000000"),
                new QuoteFreshnessSnapshot(
                        "FRESH", "新鲜", true, false, tradeDate,
                        now.minusSeconds(60), 60L, "测试"),
                null, "TAIL_CONFIRMED", "尾盘确认", "RIGHT_EARLY_ADD", "右侧早期确认",
                "尾盘量价结构确认", null, null, null, null, null,
                new BigDecimal("10.10"), new BigDecimal("10.30"), new BigDecimal("9.80"),
                List.of(), List.of(), List.of(), List.of(), null, List.of(), null
        );
        ShortTermReport report = mock(ShortTermReport.class);
        when(report.generatedAt()).thenReturn(now);
        when(report.candidates()).thenReturn(List.of(candidate));
        when(report.tradeCaptureTokens()).thenReturn(persistedTokens);
        return report;
    }
}
