package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.shortterm.chip.ChipActivationMode;
import com.aistock.research.shortterm.chip.ChipCalculationMode;
import com.aistock.research.shortterm.chip.ChipDataQuality;
import com.aistock.research.shortterm.chip.ChipOverheadPressure;
import com.aistock.research.shortterm.chip.ChipVerificationStatus;
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
        assertThat(positive.fundFlowAdjustment()).isBetween(BigDecimal.ZERO, new BigDecimal("2.00"));
        assertThat(negative.fundFlowAdjustment()).isBetween(new BigDecimal("-2.00"), BigDecimal.ZERO);
        assertThat(positive.rankingScore().subtract(negative.rankingScore()).abs())
                .isLessThanOrEqualTo(new BigDecimal("4.00"));
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
    void keepsOverheadPressureAsVisibleEvidenceWithoutDoubleCountingItInRanking() {
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
        assertThat(weakPressure.rankingScore()).isEqualByComparingTo(strongPressure.rankingScore());
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
        assertThat(missing.fundFlowAdjustment()).isEqualByComparingTo("0.00");
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
    void reportsUnavailablePressureWhenActiveButLegacySnapshotHasNoPressure() {
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

        assertThat(score.rankingScore()).isEqualByComparingTo(score.v2RankingScore());
        assertThat(score.chipContributionScore()).isEqualByComparingTo("18.00");
        assertThat(score.chipPressurePenalty()).isNull();
        assertThat(score.dataGaps()).contains("近端筹码压力未评估：本地有效筹码分布不足");
    }

    @Test
    void activeModeAppliesDistanceWeightedPenaltyToProductionRanking() {
        ShortTermSupplyDemandScore close = pressureScore(ChipActivationMode.ACTIVE,
                pressure("80"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.SINGLE_SOURCE, "10");
        ShortTermSupplyDemandScore farther = pressureScore(ChipActivationMode.ACTIVE,
                pressure("20"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.SINGLE_SOURCE, "10");

        assertThat(close.chipPressurePenalty()).isEqualByComparingTo("8.00");
        assertThat(farther.chipPressurePenalty()).isEqualByComparingTo("2.00");
        assertThat(close.rankingScore()).isEqualByComparingTo(close.v2RankingScore().subtract(new BigDecimal("8")));
        assertThat(farther.rankingScore()).isEqualByComparingTo(farther.v2RankingScore().subtract(new BigDecimal("2")));
        assertThat(close.rankingScore()).isLessThan(farther.rankingScore());
    }

    @Test
    void offAndShadowModesDoNotApplyTheEstimatedPenalty() {
        ShortTermSupplyDemandScore off = pressureScore(ChipActivationMode.OFF,
                pressure("80"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "10");
        ShortTermSupplyDemandScore shadow = pressureScore(ChipActivationMode.SHADOW,
                pressure("80"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "10");
        ShortTermSupplyDemandScore disabled = pressureScore(ChipActivationMode.ACTIVE,
                pressure("80"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "0");

        assertThat(off.chipPressurePenalty()).isNull();
        assertThat(shadow.chipPressurePenalty()).isEqualByComparingTo("0.00");
        assertThat(shadow.chipPressureSummary()).contains("估算扣分 8.00");
        assertThat(disabled.chipPressurePenalty()).isNull();
        assertThat(off.rankingScore()).isEqualByComparingTo(off.v2RankingScore());
        assertThat(shadow.rankingScore()).isEqualByComparingTo(shadow.v2RankingScore());
        assertThat(disabled.rankingScore()).isEqualByComparingTo(disabled.v2RankingScore());
    }

    @Test
    void distinguishesRealZeroPressureFromUnavailableEvaluation() {
        ShortTermSupplyDemandScore zero = pressureScore(ChipActivationMode.ACTIVE,
                pressure("0"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "10");
        ShortTermSupplyDemandScore missing = pressureScore(ChipActivationMode.ACTIVE,
                null, TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "10");

        assertThat(zero.chipPressurePenalty()).isEqualByComparingTo("0.00");
        assertThat(zero.chipPressureSummary()).contains("距离加权占比 0.00%");
        assertThat(missing.chipPressurePenalty()).isNull();
        assertThat(missing.chipPressureSummary()).contains("未评估", "不代表上方压力低");
    }

    @Test
    void refusesPenaltyForWrongDateInsufficientDistributionOrConflictingVerification() {
        ShortTermSupplyDemandScore wrongDate = pressureScore(ChipActivationMode.ACTIVE,
                pressure("80"), TRADE_DATE.minusDays(1), ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED, "10");
        ShortTermSupplyDemandScore insufficient = pressureScore(ChipActivationMode.ACTIVE,
                pressure("80"), TRADE_DATE, ChipDataQuality.INSUFFICIENT, ChipVerificationStatus.VERIFIED, "10");
        ShortTermSupplyDemandScore conflict = pressureScore(ChipActivationMode.ACTIVE,
                pressure("80"), TRADE_DATE, ChipDataQuality.VALID, ChipVerificationStatus.CONFLICT, "10");

        assertThat(wrongDate.chipPressurePenalty()).isNull();
        assertThat(wrongDate.chipPressureSummary()).contains("日期与行情日期不一致");
        assertThat(insufficient.chipPressurePenalty()).isNull();
        assertThat(insufficient.chipPressureSummary()).contains("本地有效筹码分布不足");
        assertThat(conflict.chipPressurePenalty()).isNull();
        assertThat(conflict.chipPressureSummary()).contains("模型校验未通过");
        assertThat(wrongDate.rankingScore()).isEqualByComparingTo(wrongDate.v2RankingScore());
        assertThat(insufficient.rankingScore()).isEqualByComparingTo(insufficient.v2RankingScore());
        assertThat(conflict.rankingScore()).isEqualByComparingTo(conflict.v2RankingScore());
    }

    @Test
    void appliesPenaltyAfterPositiveAdjustmentsReachTheRankingCeiling() {
        ShortTermChipSnapshot chip = pressureChip(pressure("80"), TRADE_DATE,
                ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED);

        ShortTermSupplyDemandScore active = scorer.score(null, TRADE_DATE,
                technical("10", "100", "1"), new BigDecimal("99"),
                chip, ChipActivationMode.ACTIVE, new BigDecimal("10"), new BigDecimal("11"));
        ShortTermSupplyDemandScore shadow = scorer.score(null, TRADE_DATE,
                technical("10", "100", "1"), new BigDecimal("99"),
                chip, ChipActivationMode.SHADOW, new BigDecimal("10"), new BigDecimal("11"));

        assertThat(active.chipPressurePenalty()).isEqualByComparingTo("8.00");
        assertThat(active.rankingScore()).isEqualByComparingTo("92.00");
        assertThat(shadow.rankingScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void clampsLowAdjustedRankingBeforeLimitingPressurePenalty() {
        ShortTermChipSnapshot chip = pressureChip(pressure("80"), TRADE_DATE,
                ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED);

        ShortTermSupplyDemandScore score = scorer.score(null, TRADE_DATE,
                technical("10", "100", "1"), BigDecimal.ONE,
                chip, ChipActivationMode.ACTIVE, new BigDecimal("10"), new BigDecimal("-11"));

        assertThat(score.chipPressurePenalty()).isEqualByComparingTo("0.00");
        assertThat(score.rankingScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void existingSevenArgumentOverloadKeepsZeroAdditionalAdjustment() {
        ShortTermChipSnapshot chip = pressureChip(pressure("80"), TRADE_DATE,
                ChipDataQuality.VALID, ChipVerificationStatus.VERIFIED);

        ShortTermSupplyDemandScore oldCall = scorer.score(null, TRADE_DATE,
                technical("10", "100", "1"), new BigDecimal("80"),
                chip, ChipActivationMode.ACTIVE, new BigDecimal("10"));
        ShortTermSupplyDemandScore newCall = scorer.score(null, TRADE_DATE,
                technical("10", "100", "1"), new BigDecimal("80"),
                chip, ChipActivationMode.ACTIVE, new BigDecimal("10"), BigDecimal.ZERO);

        assertThat(oldCall.rankingScore()).isEqualByComparingTo("72.00");
        assertThat(newCall.rankingScore()).isEqualByComparingTo(oldCall.rankingScore());
        assertThat(newCall.chipPressurePenalty()).isEqualByComparingTo(oldCall.chipPressurePenalty());
    }

    private ShortTermSupplyDemandScore pressureScore(
            ChipActivationMode mode,
            ChipOverheadPressure pressure,
            LocalDate chipDate,
            ChipDataQuality quality,
            ChipVerificationStatus verification,
            String maxPenalty
    ) {
        ShortTermChipSnapshot chip = pressureChip(pressure, chipDate, quality, verification);
        return scorer.score(flow("0", "1", "1", TRADE_DATE), TRADE_DATE,
                technical("10", "100", "1"), new BigDecimal("80"),
                chip, mode, new BigDecimal(maxPenalty));
    }

    private ShortTermChipSnapshot pressureChip(
            ChipOverheadPressure pressure,
            LocalDate chipDate,
            ChipDataQuality quality,
            ChipVerificationStatus verification
    ) {
        ShortTermChipSnapshot chip = mock(ShortTermChipSnapshot.class);
        when(chip.dataQuality()).thenReturn(quality);
        when(chip.shortTermPressure()).thenReturn(pressure);
        when(chip.localTradeDate()).thenReturn(chipDate);
        when(chip.verificationStatus()).thenReturn(verification);
        when(chip.verificationCoefficient()).thenReturn(verification == ChipVerificationStatus.CONFLICT
                ? BigDecimal.ZERO : BigDecimal.ONE);
        when(chip.calculationMode()).thenReturn(ChipCalculationMode.COMPLETED_BAR);
        return chip;
    }

    private ChipOverheadPressure pressure(String weighted) {
        return new ChipOverheadPressure(new BigDecimal("10.00"),
                new BigDecimal("50"), new BigDecimal("50"), new BigDecimal(weighted));
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
