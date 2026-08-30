package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FirstBoardAlertServiceTest {

    @Test
    void baselineFailureReturnsFailedWithNoCandidatesOrQuoteRequest() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        FirstBoardBaselineService baselineService = mock(FirstBoardBaselineService.class);
        LocalDate tradeDate = LocalDate.parse("2026-08-28");
        FirstBoardIngestionProgress progress = new FirstBoardIngestionProgress(
                tradeDate.minusDays(1), FirstBoardMarketDayStatus.FAILED, "FAILED",
                1832, 5120, 3, Instant.parse("2026-08-28T02:05:00Z"), "日线更新失败");
        when(baselineService.access(tradeDate)).thenReturn(FirstBoardBaselineAccess.failed(progress));
        TradingClockService clock = new TradingClockService(Clock.fixed(
                Instant.parse("2026-08-28T01:50:00Z"), ZoneId.of("Asia/Shanghai")));

        FirstBoardAlertSnapshot snapshot = new FirstBoardAlertService(
                client, baselineService, clock).snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(FirstBoardAlertStatus.FAILED);
        assertThat(snapshot.progressCompleted()).isEqualTo(1832);
        assertThat(snapshot.progressTotal()).isEqualTo(5120);
        assertThat(snapshot.progressFailed()).isEqualTo(3);
        assertThat(snapshot.latentCandidates()).isEmpty();
        assertThat(snapshot.previousLimitUpCandidates()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void gradesUntouchedFiveConditionCandidateAsAcceleratingAboveThreePointTwo() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "600000", "3.21", "10.80");

        FirstBoardAlertSnapshot snapshot = fixture.service().snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(FirstBoardAlertStatus.READY);
        assertThat(snapshot.latentCandidates()).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.level()).isEqualTo(FirstBoardAlertLevel.ACCELERATING);
                    assertThat(candidate.technicalPassCount()).isEqualTo(5);
                });
        assertThat(snapshot.previousLimitUpCandidates()).isEmpty();
    }

    @Test
    void keepsPreviousLimitUpInSeparateLaneAndDowngradesAfterTenThirty() {
        Fixture fixture = fixtureAt("2026-08-28T02:40:00Z", true, "600000", "3.50", "10.80");

        FirstBoardAlertSnapshot snapshot = fixture.service().snapshot(false, false);

        assertThat(snapshot.latentCandidates()).isEmpty();
        assertThat(snapshot.previousLimitUpCandidates()).singleElement()
                .extracting(FirstBoardAlertCandidate::level)
                .isEqualTo(FirstBoardAlertLevel.REPEAT_STRONG);
    }

    @Test
    void excludesCandidateThatAlreadyTouchedItsExactLimitUpPrice() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "600000", "3.50", "11.00");

        FirstBoardAlertSnapshot snapshot = fixture.service().snapshot(false, false);

        assertThat(snapshot.latentCandidates()).isEmpty();
        assertThat(snapshot.coverage().excludedTouchedLimitCount()).isEqualTo(1);
    }

    @Test
    void excludesGrowthBoardByDefaultAndIncludesItWhenSwitchIsOn() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "300001", "1.30", "11.00");

        assertThat(fixture.service().snapshot(false, false).latentCandidates()).isEmpty();
        assertThat(fixture.service().snapshot(true, false).latentCandidates()).hasSize(1);
    }

    @Test
    void keepsExactlyFourTechnicalConditionsAtWatchEvenWithHighVolumeRatio() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "600000", "3.50", "10.80", 4);

        assertThat(fixture.service().snapshot(false, false).latentCandidates()).singleElement()
                .extracting(FirstBoardAlertCandidate::level)
                .isEqualTo(FirstBoardAlertLevel.WATCH);
    }

    @Test
    void keepsThreePointTwoInsideStrongInsteadOfAccelerating() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "600000", "3.20", "10.80");

        assertThat(fixture.service().snapshot(false, false).latentCandidates()).singleElement()
                .extracting(FirstBoardAlertCandidate::level)
                .isEqualTo(FirstBoardAlertLevel.STRONG);
    }

    @Test
    void excludesRealtimeVolumeRatioBelowPointEight() {
        Fixture fixture = fixtureAt("2026-08-28T01:50:00Z", false, "600000", "0.79", "10.80");

        FirstBoardAlertSnapshot snapshot = fixture.service().snapshot(false, false);

        assertThat(snapshot.latentCandidates()).isEmpty();
        assertThat(snapshot.coverage().excludedVolumeRatioCount()).isEqualTo(1);
    }

    @Test
    void capsPreviousLimitUpAtWatchFromElevenOClock() {
        Fixture fixture = fixtureAt("2026-08-28T03:01:00Z", true, "600000", "4.20", "10.80");

        assertThat(fixture.service().snapshot(false, false).previousLimitUpCandidates()).singleElement()
                .extracting(FirstBoardAlertCandidate::level)
                .isEqualTo(FirstBoardAlertLevel.WATCH);
    }

    @Test
    void scansThePreviousTradingDayCloseWhenTodayIsNotATradingDay() {
        Fixture fixture = fixtureAt("2026-08-30T01:50:00Z", true, "600000", "4.20", "10.80");

        FirstBoardAlertSnapshot snapshot = fixture.service().snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(FirstBoardAlertStatus.READY);
        assertThat(snapshot.tradeDate()).isEqualTo(LocalDate.parse("2026-08-28"));
        assertThat(snapshot.autoRefreshAllowed()).isFalse();
        assertThat(snapshot.previousLimitUpCandidates()).singleElement()
                .extracting(FirstBoardAlertCandidate::level)
                .isEqualTo(FirstBoardAlertLevel.WATCH);
    }

    private Fixture fixtureAt(
            String instant,
            boolean previousLimitUp,
            String symbol,
            String realtimeVolumeRatio,
            String highPrice
    ) {
        return fixtureAt(instant, previousLimitUp, symbol, realtimeVolumeRatio, highPrice, 5);
    }

    private Fixture fixtureAt(
            String instant,
            boolean previousLimitUp,
            String symbol,
            String realtimeVolumeRatio,
            String highPrice,
            int technicalPassCount
    ) {
        LocalDate tradeDate = LocalDate.parse("2026-08-28");
        FirstBoardBaseline baseline = new FirstBoardBaseline(
                symbol, "测试股份", "测试行业", FirstBoardBoard.fromSymbol(symbol),
                tradeDate.minusDays(1), new BigDecimal("10.00"),
                new BigDecimal("9.80"), new BigDecimal("9.50"), new BigDecimal("2.10"),
                new BigDecimal("61"), new BigDecimal("0.12"), technicalPassCount,
                new BigDecimal("0.70"), new BigDecimal("1.80"), new BigDecimal("1.20"),
                new BigDecimal("2.40"), previousLimitUp, List.of()
        );
        FirstBoardBaselineSnapshot baselineSnapshot = new FirstBoardBaselineSnapshot(
                tradeDate, tradeDate.minusDays(1), Instant.parse("2026-08-28T01:20:00Z"),
                Map.of(symbol, baseline), 1, 1, 0, 0
        );
        FirstBoardBaselineService baselineService = mock(FirstBoardBaselineService.class);
        when(baselineService.access(tradeDate)).thenReturn(FirstBoardBaselineAccess.ready(baselineSnapshot));

        EastMoneyClient client = mock(EastMoneyClient.class);
        EastMoneyQuote quote = new EastMoneyQuote(
                symbol, "测试股份", "沪深", "测试行业",
                new BigDecimal("10.50"), new BigDecimal("5.00"), new BigDecimal("3.00"),
                new BigDecimal("1000000"), new BigDecimal("10000000"), null, null, null,
                "东方财富行情", "https://example.test", Instant.parse(instant),
                tradeDate, Instant.parse(instant), null,
                new BigDecimal("10.10"), new BigDecimal(highPrice), new BigDecimal("10.00"),
                new BigDecimal(realtimeVolumeRatio)
        );
        when(client.fetchAshareQuoteSnapshot(6000)).thenReturn(new AshareQuoteSnapshot(
                List.of(quote), 1, 1, 1, 0, true, "test", Instant.parse(instant)
        ));
        TradingClockService clockService = new TradingClockService(Clock.fixed(
                Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
        return new Fixture(new FirstBoardAlertService(client, baselineService, clockService));
    }

    private record Fixture(FirstBoardAlertService service) {
    }
}
