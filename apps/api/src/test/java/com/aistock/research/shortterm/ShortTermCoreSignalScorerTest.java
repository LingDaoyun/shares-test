package com.aistock.research.shortterm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermCoreSignalScorerTest {

    private final ShortTermCoreSignalScorer scorer = new ShortTermCoreSignalScorer();

    @Test
    void appliesTheApprovedFourSignalWeights() {
        ShortTermCoreSignalScore result = scorer.score(
                goldenCross("CONFIRMED", 1, "WIDENING", 4),
                new BigDecimal("1.80"),
                new BigDecimal("1.20"),
                momentum("3.00", "100", "95", false)
        );

        assertThat(result.goldenCrossScore()).isEqualByComparingTo("100.00");
        assertThat(result.volumeScore()).isEqualByComparingTo("100.00");
        assertThat(result.turnoverScore()).isEqualByComparingTo("100.00");
        assertThat(result.closeStrengthScore()).isEqualByComparingTo("95.00");
        assertThat(result.finalScore()).isEqualByComparingTo("99.70");
    }

    @Test
    void confirmedRecentGoldenCrossOutranksAFormingCrossWithOtherwiseIdenticalSignals() {
        ShortTermCoreSignalScore confirmed = scorer.score(
                goldenCross("CONFIRMED", 2, "WIDENING", 4),
                new BigDecimal("1.50"),
                new BigDecimal("1.00"),
                momentum("3.00", "100", "95", false)
        );
        ShortTermCoreSignalScore forming = scorer.score(
                goldenCross("FORMING", null, "NARROWING", 3),
                new BigDecimal("1.50"),
                new BigDecimal("1.00"),
                momentum("3.00", "100", "95", false)
        );

        assertThat(confirmed.goldenCrossScore()).isGreaterThan(forming.goldenCrossScore());
        assertThat(confirmed.finalScore()).isGreaterThan(forming.finalScore());
    }

    @Test
    void rewardsRisingModerateVolumeAndPenalizesDryOrExcessiveVolume() {
        ShortTermGoldenCrossSnapshot cross = goldenCross("CONFIRMED", 1, "WIDENING", 4);
        ShortTermMomentumQuality momentum = momentum("3.00", "100", "95", false);

        ShortTermCoreSignalScore moderate = scorer.score(cross, new BigDecimal("1.80"), BigDecimal.ONE, momentum);
        ShortTermCoreSignalScore dry = scorer.score(cross, new BigDecimal("0.80"), BigDecimal.ONE, momentum);
        ShortTermCoreSignalScore excessive = scorer.score(cross, new BigDecimal("4.30"), BigDecimal.ONE, momentum);
        ShortTermCoreSignalScore falling = scorer.score(cross, new BigDecimal("1.80"), new BigDecimal("-0.50"), momentum);

        assertThat(moderate.volumeScore()).isGreaterThan(dry.volumeScore());
        assertThat(moderate.volumeScore()).isGreaterThan(excessive.volumeScore());
        assertThat(moderate.volumeScore()).isGreaterThan(falling.volumeScore());
    }

    @Test
    void usesTheConfiguredMinimumVolumeRatio() {
        ShortTermGoldenCrossSnapshot cross = goldenCross("CONFIRMED", 1, "WIDENING", 4);
        ShortTermMomentumQuality momentum = momentum("3.00", "100", "95", false);

        ShortTermCoreSignalScore defaultThreshold = scorer.score(
                cross, new BigDecimal("1.30"), BigDecimal.ONE, momentum, new BigDecimal("1.20"));
        ShortTermCoreSignalScore raisedThreshold = scorer.score(
                cross, new BigDecimal("1.30"), BigDecimal.ONE, momentum, new BigDecimal("1.50"));

        assertThat(defaultThreshold.volumeScore()).isEqualByComparingTo("100");
        assertThat(raisedThreshold.volumeScore()).isEqualByComparingTo("65");
    }

    @Test
    void neverTreatsSubOneVolumeRatioAsVolumeConfirmation() {
        ShortTermCoreSignalScore result = scorer.score(
                goldenCross("CONFIRMED", 1, "WIDENING", 4),
                new BigDecimal("0.90"),
                BigDecimal.ONE,
                momentum("3.00", "100", "95", false),
                new BigDecimal("0.80")
        );

        assertThat(result.volumeScore()).isEqualByComparingTo("40");
    }

    private ShortTermGoldenCrossSnapshot goldenCross(
            String state,
            Integer days,
            String spreadTrend,
            int priority
    ) {
        return new ShortTermGoldenCrossSnapshot(
                ShortTermGoldenCrossSnapshot.RULE_VERSION,
                state,
                state,
                null,
                days,
                new BigDecimal("0.20"),
                spreadTrend,
                "MA5_ABOVE_MA10",
                priority,
                "AVAILABLE"
        );
    }

    private ShortTermMomentumQuality momentum(
            String turnoverRate,
            String turnoverScore,
            String closeStrengthScore,
            boolean extremeUpperShadow
    ) {
        return new ShortTermMomentumQuality(
                new BigDecimal(turnoverRate),
                "PREFERRED",
                new BigDecimal(turnoverScore),
                new BigDecimal("10"),
                new BigDecimal("15"),
                new BigDecimal("90"),
                "上攻收盘强",
                new BigDecimal(closeStrengthScore),
                false,
                extremeUpperShadow,
                List.of()
        );
    }
}
