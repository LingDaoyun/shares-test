package com.aistock.research.shortterm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermSignalProfileResolverTest {

    private final ShortTermSignalProfileResolver resolver = new ShortTermSignalProfileResolver();

    @Test
    void contractionBreakoutRemainsAnIndependentPrimaryFamily() {
        ShortTermVolatilityQuality volatility = new ShortTermVolatilityQuality(
                new BigDecimal("2.00"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("1.30"), new BigDecimal("0.60"),
                "CONTRACTION_BREAKOUT", "收缩后扩张突破", true, new BigDecimal("3.00"), List.of()
        );

        ShortTermSignalProfile profile = resolver.resolve(snapshot(), volatility);

        assertThat(profile.primaryFamily()).isEqualTo("VOLATILITY_CONTRACTION_BREAKOUT");
        assertThat(profile.activeFamilies()).contains(
                "VOLATILITY_CONTRACTION_BREAKOUT",
                "GOLDEN_CROSS_MOMENTUM"
        );
        assertThat(profile.evidence()).anyMatch(value -> value.contains("ATR"));
    }

    @Test
    void unavailableVolatilityNeverReceivesAnOptimisticFamily() {
        ShortTermSignalProfile profile = resolver.resolve(
                snapshot(),
                ShortTermVolatilityQuality.unavailable("ATR缺失")
        );

        assertThat(profile.primaryFamily()).isEqualTo("GOLDEN_CROSS_MOMENTUM");
        assertThat(profile.activeFamilies()).doesNotContain("VOLATILITY_CONTRACTION_BREAKOUT");
        assertThat(profile.dataGaps()).contains("ATR缺失");
    }

    private ShortTermTechnicalSnapshot snapshot() {
        return new ShortTermTechnicalSnapshot(
                null,
                new BigDecimal("11.40"), new BigDecimal("11.20"), new BigDecimal("11.00"), new BigDecimal("10.50"),
                new BigDecimal("0.30"), new BigDecimal("0.10"), new BigDecimal("11.66"), new BigDecimal("12.00"),
                new BigDecimal("1.20"), new BigDecimal("8.00"), new BigDecimal("12.00"), new BigDecimal("8.00"),
                new BigDecimal("1.40"), new BigDecimal("1.30"), new BigDecimal("70"), new BigDecimal("65"),
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("4.00"), 3,
                "右侧早期确认",
                new ShortTermGoldenCrossSnapshot(
                        ShortTermGoldenCrossSnapshot.RULE_VERSION,
                        "CONFIRMED", "金叉确认", null, 2,
                        new BigDecimal("0.20"), "WIDENING", "MA5_ABOVE_MA10", 1, "AVAILABLE"
                ),
                new BigDecimal("2.00"),
                new BigDecimal("10.50")
        );
    }
}
