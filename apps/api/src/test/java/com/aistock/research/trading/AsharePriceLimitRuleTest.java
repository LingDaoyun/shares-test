package com.aistock.research.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AsharePriceLimitRuleTest {

    @Test
    void usesBoardSpecificLimitUpThresholds() {
        assertThat(AsharePriceLimitRule.isLimitUpLike("600000", new BigDecimal("9.60"))).isTrue();
        assertThat(AsharePriceLimitRule.isLimitUpLike("300750", new BigDecimal("9.60"))).isFalse();
        assertThat(AsharePriceLimitRule.isLimitUpLike("300750", new BigDecimal("19.10"))).isTrue();
        assertThat(AsharePriceLimitRule.isLimitUpLike("688256", new BigDecimal("19.10"))).isTrue();
        assertThat(AsharePriceLimitRule.isLimitUpLike("920001", new BigDecimal("28.60"))).isTrue();
    }

    @Test
    void appliesTheSameBoardThresholdToLimitDownApproximation() {
        assertThat(AsharePriceLimitRule.isLimitDownLike("000001", new BigDecimal("-9.60"))).isTrue();
        assertThat(AsharePriceLimitRule.isLimitDownLike("688001", new BigDecimal("-9.60"))).isFalse();
        assertThat(AsharePriceLimitRule.isLimitDownLike("688001", new BigDecimal("-19.10"))).isTrue();
    }

    @Test
    void calculatesExactBoardSpecificLimitUpPriceAndTouch() {
        assertThat(AsharePriceLimitRule.limitUpPrice("600000", new BigDecimal("10.01")))
                .isEqualByComparingTo("11.01");
        assertThat(AsharePriceLimitRule.limitUpPrice("300750", new BigDecimal("10.01")))
                .isEqualByComparingTo("12.01");
        assertThat(AsharePriceLimitRule.limitUpPrice("689009", new BigDecimal("10.01")))
                .isEqualByComparingTo("12.01");
        assertThat(AsharePriceLimitRule.hasTouchedLimitUp(
                "600000", new BigDecimal("10.01"), new BigDecimal("11.01"))).isTrue();
        assertThat(AsharePriceLimitRule.hasTouchedLimitUp(
                "600000", new BigDecimal("10.01"), new BigDecimal("11.00"))).isFalse();
    }
}
