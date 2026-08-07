package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.shortterm.chip.ChipActivationMode;
import com.aistock.research.shortterm.chip.ShortTermChipSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortTermSupplyDemandScorerTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-07-31");

    private final ShortTermSupplyDemandScorer scorer = new ShortTermSupplyDemandScorer();

    @Test
    void strongerBuyingPressureOutranksNegativeMainFlow() {
        ShortTermTechnicalSnapshot technical = technical("12", "90", "1");

        ShortTermSupplyDemandScore positive = scorer.score(
                flow("8", "3", "2", TRADE_DATE),
                TRADE_DATE,
                technical,
                new BigDecimal("80"));
        ShortTermSupplyDemandScore negative = scorer.score(
                flow("-6", "-2", "-1", TRADE_DATE),
                TRADE_DATE,
                technical,
                new BigDecimal("80"));

        assertThat(positive.buyPressureScore()).isGreaterThan(negative.buyPressureScore());
        assertThat(positive.rankingScore()).isGreaterThan(negative.rankingScore());
    }

    @Test
    void rewardsConsistentSuperLargeAndLargeOrderBuying() {
        ShortTermTechnicalSnapshot technical = technical("12", "90", "1");

        ShortTermSupplyDemandScore consistent = scorer.score(
                flow("0", "1", "1", TRADE_DATE),
                TRADE_DATE,
                technical,
                new BigDecimal("70"));
        ShortTermSupplyDemandScore selling = scorer.score(
                flow("0", "-1", "-1", TRADE_DATE),
                TRADE_DATE,
                technical,
                new BigDecimal("70"));

        assertThat(consistent.largeOrderNetInflowRatio()).isEqualByComparingTo("2.00");
        assertThat(consistent.buyPressureScore()).isEqualByComparingTo("60.00");
        assertThat(selling.buyPressureScore()).isEqualByComparingTo("47.00");
    }

    @Test
    void weakOverheadPressureOutranksLongUpperShadowNearResistance() {
        EastMoneyFundFlowSnapshot flow = flow("5", "2", "1", TRADE_DATE);

        ShortTermSupplyDemandScore weakPressure = scorer.score(
                flow,
                TRADE_DATE,
                technical("10", "92", "1"),
                new BigDecimal("75"));
        ShortTermSupplyDemandScore strongPressure = scorer.score(
                flow,
                TRADE_DATE,
                technical("60", "45", "-1"),
                new BigDecimal("75"));

        assertThat(weakPressure.overheadPressureReliefScore())
                .isGreaterThan(strongPressure.overheadPressureReliefScore());
        assertThat(weakPressure.rankingScore()).isGreaterThan(strongPressure.rankingScore());
    }

    @Test
    void wrongDateOrMissingFundFlowReceivesLowBuyingScore() {
        ShortTermTechnicalSnapshot technical = technical("15", "85", "2");

        ShortTermSupplyDemandScore wrongDate = scorer.score(
                flow("12", "5", "4", TRADE_DATE.minusDays(1)),
                TRADE_DATE,
                technical,
                new BigDecimal("90"));
        ShortTermSupplyDemandScore missing = scorer.score(
                null,
                TRADE_DATE,
                technical,
                new BigDecimal("90"));

        assertThat(wrongDate.buyPressureScore()).isEqualByComparingTo("35.00");
        assertThat(wrongDate.dataGaps()).contains("资金流交易日与行情交易日不一致");
        assertThat(missing.buyPressureScore()).isEqualByComparingTo("35.00");
        assertThat(missing.dataGaps()).contains("东方财富资金流缺失");
        assertThat(missing.rankingScore()).isEqualByComparingTo("90.00");
    }

    @Test
    void clampsEveryScoreToZeroThroughOneHundred() {
        ShortTermSupplyDemandScore score = scorer.score(
                flow("1000", "500", "500", TRADE_DATE),
                TRADE_DATE,
                technical("-100", "1000", "1000"),
                new BigDecimal("1000"));

        assertThat(List.of(
                score.buyPressureScore(),
                score.overheadPressureReliefScore(),
                score.technicalRankingScore(),
                score.rankingScore()
        )).allSatisfy(value -> assertThat(value)
                .isBetween(new BigDecimal("0.00"), new BigDecimal("100.00")));
    }

    @Test
    void keepsVerifiedChipScoreSeparateFromAppliedRankingInShadowMode() {
        ShortTermTechnicalSnapshot technical = technical("10", "100", "1");
        ShortTermChipSnapshot chip = mock(ShortTermChipSnapshot.class);
        when(chip.contributionScore()).thenReturn(new BigDecimal("20"));

        ShortTermSupplyDemandScore score = scorer.score(
                flow("0", "1", "1", TRADE_DATE),
                TRADE_DATE,
                technical,
                new BigDecimal("80"),
                chip,
                ChipActivationMode.SHADOW
        );

        assertThat(score.rankingScore()).isEqualByComparingTo(score.v2RankingScore());
        assertThat(score.chipContributionScore()).isEqualByComparingTo("20.00");
        assertThat(score.v3RankingScore()).isNotNull();
    }

    @Test
    void appliesTheConfiguredV3WeightsWhenActivationModeIsActive() {
        ShortTermChipSnapshot chip = mock(ShortTermChipSnapshot.class);
        when(chip.contributionScore()).thenReturn(new BigDecimal("18"));

        ShortTermSupplyDemandScore score = scorer.score(
                flow("4", "1", "1", TRADE_DATE),
                TRADE_DATE,
                technical("12", "90", "1"),
                new BigDecimal("75"),
                chip,
                ChipActivationMode.ACTIVE
        );

        assertThat(score.v3RankingScore()).isEqualByComparingTo("77.21");
        assertThat(score.rankingScore()).isEqualByComparingTo("77.21");
        assertThat(score.chipContributionScore()).isEqualByComparingTo("18.00");
        assertThat(score.rankingScore()).isNotEqualByComparingTo(score.v2RankingScore());
    }

    private EastMoneyFundFlowSnapshot flow(
            String mainRatio,
            String superLargeRatio,
            String largeRatio,
            LocalDate tradeDate
    ) {
        return new EastMoneyFundFlowSnapshot(
                "600000",
                "样本",
                new BigDecimal("100000000"),
                new BigDecimal("60000000"),
                new BigDecimal("40000000"),
                BigDecimal.ZERO,
                new BigDecimal("-100000000"),
                new BigDecimal(mainRatio),
                new BigDecimal(superLargeRatio),
                new BigDecimal(largeRatio),
                BigDecimal.ZERO,
                new BigDecimal("-10"),
                "东方财富资金流",
                "https://quote.eastmoney.com/sh600000.html",
                Instant.parse("2026-07-31T07:01:00Z"),
                tradeDate,
                Instant.parse("2026-07-31T07:00:00Z")
        );
    }

    private ShortTermTechnicalSnapshot technical(
            String upperShadow,
            String closeLocation,
            String breakout
    ) {
        ShortTermMomentumQuality quality = mock(ShortTermMomentumQuality.class);
        when(quality.bullishUpperShadowMedian3Percent()).thenReturn(new BigDecimal(upperShadow));
        when(quality.latestUpperShadowPercent()).thenReturn(new BigDecimal(upperShadow));
        when(quality.closeLocationPercent()).thenReturn(new BigDecimal(closeLocation));
        ShortTermTechnicalSnapshot technical = mock(ShortTermTechnicalSnapshot.class);
        when(technical.momentumQuality()).thenReturn(quality);
        when(technical.breakoutFromPreviousHigh20Percent()).thenReturn(new BigDecimal(breakout));
        return technical;
    }
}
