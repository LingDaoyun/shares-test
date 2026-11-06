package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ShortTermResearchIntegrationTest {
    @Test
    void highResearchScoreCannotOverrideUntrustedHistoryOrStaleQuotes() {
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class));
        EastMoneyQuote quote = mock(EastMoneyQuote.class);
        when(quote.latestPrice()).thenReturn(new BigDecimal("10.20"));
        when(quote.changePercent()).thenReturn(BigDecimal.ONE);
        ShortTermTechnicalSnapshot technical = mock(ShortTermTechnicalSnapshot.class);
        ShortTermMomentumQuality momentum = mock(ShortTermMomentumQuality.class);
        when(technical.momentumQuality()).thenReturn(momentum);
        when(momentum.turnoverBand()).thenReturn("PREFERRED");
        ShortTermFinancialSnapshot financial = mock(ShortTermFinancialSnapshot.class);
        when(financial.reportDate()).thenReturn("2025-12-31");
        when(financial.qualityScore()).thenReturn(new BigDecimal("80"));
        QuoteFreshnessSnapshot freshness = mock(QuoteFreshnessSnapshot.class);
        ShortTermMarketSentiment market = mock(ShortTermMarketSentiment.class);
        ShortTermRuleSet rules = mock(ShortTermRuleSet.class);
        when(rules.maxEntryRisePercent()).thenReturn(new BigDecimal("6.5"));
        when(rules.maxDistanceToMa20Percent()).thenReturn(new BigDecimal("8"));
        ShortTermResearchSetup research = mock(ShortTermResearchSetup.class);
        when(research.setupType()).thenReturn("CONTRACTION_BREAKOUT");
        when(research.setupLabel()).thenReturn("收缩突破");
        when(research.score()).thenReturn(new BigDecimal("95"));
        when(research.relativeStrengthScore()).thenReturn(new BigDecimal("90"));
        when(research.triggerPrice()).thenReturn(new BigDecimal("10.10"));
        when(research.invalidationPrice()).thenReturn(new BigDecimal("10.00"));
        when(research.historicalStructureCurrent()).thenReturn(true);
        java.util.function.Supplier<String> action = () -> {
            Object decision = ReflectionTestUtils.invokeMethod(service, "decide", quote, technical, financial,
                    freshness, market, null, new BigDecimal("80"), new BigDecimal("80"),
                    new BigDecimal("80"), rules, research);
            return ReflectionTestUtils.invokeMethod(decision, "action");
        };
        assertThat(action.get()).isEqualTo("WATCH_RIGHT_SIDE");
        when(research.executionEvidenceReady()).thenReturn(true);
        assertThat(action.get()).isEqualTo("RESEARCH_LIGHT_TRIAL");
        when(research.setupType()).thenReturn("GOLDEN_CROSS_MOMENTUM");
        when(research.historicalStructureCurrent()).thenReturn(false);
        assertThat(action.get()).isEqualTo("DATA_REVIEW");
        when(research.historicalStructureCurrent()).thenReturn(true);
        when(freshness.blocksRealtimeDecision()).thenReturn(true);
        assertThat(action.get()).isEqualTo("DATA_REVIEW");
    }

    @Test
    void independentBreakoutPassesAdmissionWithoutGoldenCrossButMissingVolumeStillBlocks() {
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class));
        EastMoneyQuote quote = mock(EastMoneyQuote.class);
        when(quote.changePercent()).thenReturn(BigDecimal.ONE);
        ShortTermTechnicalSnapshot technical = mock(ShortTermTechnicalSnapshot.class);
        when(technical.tradeDate()).thenReturn(LocalDate.of(2026, 7, 23));
        when(technical.volumeRatio20()).thenReturn(new BigDecimal("1.5"));
        ShortTermResearchSetup research = mock(ShortTermResearchSetup.class);
        when(research.setupType()).thenReturn("CONTRACTION_BREAKOUT");
        ShortTermRiskExclusion admitted = ReflectionTestUtils.invokeMethod(service,
                "technicalHardExclusion", quote, technical, research);
        assertThat(admitted).isNull();
        when(technical.volumeRatio20()).thenReturn(null);
        ShortTermRiskExclusion rejected = ReflectionTestUtils.invokeMethod(service,
                "technicalHardExclusion", quote, technical, research);
        assertThat(rejected).isNotNull();
        assertThat(rejected.category()).isEqualTo("VOLUME_DATA_MISSING");
    }

    @Test
    void blockedPlanDowngradesAdviceInsteadOfLeavingBuyAction() {
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class));
        ShortTermCandidate candidate = candidate("600001", "98", "ACTIONABLE", "LIGHT_TRIAL");
        ShortTermTradePlan blocked = mock(ShortTermTradePlan.class);
        when(blocked.status()).thenReturn("BLOCKED");
        when(blocked.blockedReasons()).thenReturn(List.of("净风险收益比不足"));
        ShortTermCandidate result = ReflectionTestUtils.invokeMethod(service, "copyWithTradePlan", candidate, blocked);
        assertThat(result.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(result.todayAdvice().reasons()).contains("净风险收益比不足");
    }

    @Test
    void executablePlanRanksBeforeHigherScoredBlockedResearch() {
        ShortTermCandidate blocked = candidate("600001", "98", "BLOCKED", "LIGHT_TRIAL");
        ShortTermCandidate executable = candidate("600002", "78", "ACTIONABLE", "LIGHT_TRIAL");
        assertThat(List.of(blocked, executable).stream()
                .sorted(ShortTermService.researchCandidateComparator()).toList())
                .containsExactly(executable, blocked);
    }

    @Test
    void setupScoreOrdersObservationWithoutGoldenCrossPriority() {
        ShortTermCandidate lower = candidate("600001", "70", "BLOCKED", "WAIT");
        ShortTermCandidate higher = candidate("600002", "86", "BLOCKED", "WAIT");
        assertThat(List.of(lower, higher).stream()
                .sorted(ShortTermService.researchCandidateComparator()).toList())
                .containsExactly(higher, lower);
    }

    @Test
    void newResearchRequiresConfirmedTailAndNeverUsesFutureMinute() {
        assertThat(adjust("WATCH", "14:46").action()).isEqualTo("WAIT");
        assertThat(adjust("CONFIRMED", "14:47").action()).isEqualTo("WAIT");
        assertThat(adjust("CONFIRMED", "14:46").action()).isEqualTo("LIGHT_TRIAL");
    }

    @Test
    void strongTailCannotBypassMissingIntradayPullbackSequence() {
        ShortTermTailSignal tail = new ShortTermTailSignal("CONFIRMED", "尾盘确认", true,
                "2026-07-23", "14:46", new BigDecimal("10.5"), new BigDecimal("10.3"),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("30000000"),
                new BigDecimal("5"), new BigDecimal("80"), List.of(), List.of());
        ShortTermIntradayPullbackSignal missing = new ShortTermIntradayPullbackSignal(
                "UNAVAILABLE", "分时数据不足", null, null, null, null, null, null,
                List.of(), List.of("缺少连续分钟"));
        ShortTermTailSignal gated = tail.withIntradayPullback(missing);
        assertThat(gated.status()).isEqualTo("PULLBACK_UNAVAILABLE");
        assertThat(gated.intradayPullback()).isEqualTo(missing);
        assertThat(gated.reasons()).contains("缺少连续分钟");
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class),
                new EvidenceCompletenessService(), new TradingClockService(
                Clock.fixed(Instant.parse("2026-07-23T06:46:00Z"), ZoneId.of("Asia/Shanghai"))));
        TradingAdvice base = new TradingAdvice("LIGHT_TRIAL", "研究候选", 60, "", List.of(), List.of());
        TradingAdvice result = ReflectionTestUtils.invokeMethod(service, "tailAdjustedAdvice", base,
                "RESEARCH_LIGHT_TRIAL", gated, true);
        assertThat(result.action()).isEqualTo("WAIT");
    }

    @Test
    void pullbackDoesNotFallbackToSampledMinuteHighsAndLows() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        when(client.fetchIntradayOhlcv("600001")).thenReturn(List.of());
        ShortTermService service = new ShortTermService(client, new EvidenceCompletenessService(),
                new TradingClockService(Clock.fixed(Instant.parse("2026-07-23T06:48:00Z"), ZoneId.of("Asia/Shanghai"))));
        ShortTermTailSignal signal = ReflectionTestUtils.invokeMethod(service, "tailSignal",
                "600001", LocalDate.of(2026, 7, 23), true);
        assertThat(signal.status()).isEqualTo("PULLBACK_UNAVAILABLE");
        assertThat(signal.intradayPullback().confirmed()).isFalse();
        verify(client).fetchIntradayOhlcv("600001");
        verify(client, never()).fetchIntradayTrends(anyString());
    }

    @Test
    void duplicateMinuteCannotSupplyAThirdTailObservation() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        LocalDate date = LocalDate.of(2026, 7, 23);
        List<EastMoneyIntradayPoint> minutes = new ArrayList<>();
        for (int i = 1; i <= 106; i++) {
            String close = i <= 20 ? "10.12" : i <= 30 ? "10.03" : i <= 40 ? "10.15" : i == 106 ? "10.20" : "10.16";
            String high = i <= 20 ? "10.14" : i <= 30 ? "10.10" : "10.22";
            String low = i <= 20 ? "10.08" : i <= 30 ? "10.02" : "10.04";
            String volume = i <= 20 ? "100" : i <= 30 ? "60" : i <= 40 ? "180" : "100";
            minutes.add(new EastMoneyIntradayPoint("600001", date.atTime(13, 0).plusMinutes(i),
                    new BigDecimal(close), new BigDecimal(close), new BigDecimal(high), new BigDecimal(low),
                    new BigDecimal(volume), new BigDecimal(i >= 105 ? "10000000" : "10000"), new BigDecimal("10.00")));
        }
        minutes.add(minutes.get(minutes.size() - 1));
        when(client.fetchIntradayOhlcv("600001")).thenReturn(minutes);
        ShortTermService service = new ShortTermService(client, new EvidenceCompletenessService(),
                new TradingClockService(Clock.fixed(Instant.parse("2026-07-23T06:47:00Z"), ZoneId.of("Asia/Shanghai"))));
        ShortTermTailSignal signal = ReflectionTestUtils.invokeMethod(service, "tailSignal", "600001", date, true);
        assertThat(signal.intradayPullback().confirmed()).isTrue();
        assertThat(signal.status()).isEqualTo("WATCH");
        assertThat(signal.tailAmount()).isEqualByComparingTo("20000000");
    }

    private TradingAdvice adjust(String status, String minute) {
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class),
                new EvidenceCompletenessService(), new TradingClockService(
                Clock.fixed(Instant.parse("2026-07-23T06:46:00Z"), ZoneId.of("Asia/Shanghai"))));
        TradingAdvice base = new TradingAdvice("LIGHT_TRIAL", "研究候选", 60,
                "结构匹配，等待尾盘", List.of(), List.of());
        ShortTermTailSignal tail = new ShortTermTailSignal(status, status, true,
                "2026-07-23", minute, new BigDecimal("10.5"), new BigDecimal("10.3"),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("30000000"),
                new BigDecimal("5"), new BigDecimal("80"), List.of(), List.of());
        return ReflectionTestUtils.invokeMethod(service, "tailAdjustedAdvice", base,
                "RESEARCH_LIGHT_TRIAL", tail, true);
    }

    private ShortTermCandidate candidate(String symbol, String score, String status, String advice) {
        ShortTermCandidate candidate = mock(ShortTermCandidate.class);
        ShortTermSignalProfile profile = mock(ShortTermSignalProfile.class);
        ShortTermResearchSetup research = mock(ShortTermResearchSetup.class);
        ShortTermTradePlan plan = mock(ShortTermTradePlan.class);
        when(candidate.symbol()).thenReturn(symbol);
        when(candidate.action()).thenReturn("RESEARCH_LIGHT_TRIAL");
        when(candidate.signalProfile()).thenReturn(profile);
        when(profile.research()).thenReturn(research);
        when(research.score()).thenReturn(new BigDecimal(score));
        when(research.setupType()).thenReturn("CONTRACTION_BREAKOUT");
        when(candidate.tradePlan()).thenReturn(plan);
        when(plan.status()).thenReturn(status);
        when(candidate.todayAdvice()).thenReturn(new TradingAdvice(advice, advice, 60, "", List.of(), List.of()));
        return candidate;
    }
}
