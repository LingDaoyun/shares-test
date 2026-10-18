package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
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
