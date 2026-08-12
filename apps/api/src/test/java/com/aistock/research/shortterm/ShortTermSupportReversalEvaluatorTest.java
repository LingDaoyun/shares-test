package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermSupportReversalEvaluatorTest {

    private final ShortTermSupportReversalEvaluator evaluator = new ShortTermSupportReversalEvaluator();

    @Test
    void confirmsSlightDeclineThatClosesNearTheHighAfterReclaimingSupport() {
        ShortTermSupportReversalSignal signal = evaluate(
                "-1.00",
                row("10.70", "10.62", "10.72", "10.18"),
                snapshot("0.10", "1.30")
        );

        assertThat(signal.confirmed()).isTrue();
        assertThat(signal.state()).isEqualTo("CONFIRMED");
        assertThat(signal.supportType()).isEqualTo("MA5");
        assertThat(signal.supportPrice()).isEqualByComparingTo("10.60");
        assertThat(signal.lowerShadowPercent()).isGreaterThanOrEqualTo(new BigDecimal("50"));
        assertThat(signal.closeLocationPercent()).isGreaterThanOrEqualTo(new BigDecimal("70"));
        assertThat(signal.score()).isBetween(new BigDecimal("70"), new BigDecimal("100"));
    }

    @Test
    void rejectsOrdinaryCandleWithoutLongLowerShadow() {
        ShortTermSupportReversalSignal signal = evaluate(
                "-1.00",
                row("10.50", "10.62", "10.72", "10.45"),
                snapshot("0.10", "1.30")
        );

        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("下影线"));
    }

    @Test
    void rejectsSignalWhenMa20TrendIsFallingTooFast() {
        ShortTermSupportReversalSignal signal = evaluate(
                "-1.00",
                row("10.70", "10.62", "10.72", "10.18"),
                snapshot("-0.30", "1.30")
        );

        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.trendQualified()).isFalse();
    }

    @Test
    void respectsTheConfiguredMaximumDistanceToMa20() {
        EastMoneyKLine latest = row("10.70", "10.62", "10.72", "10.18");
        ShortTermMomentumQuality momentum = new ShortTermMomentumQualityEvaluator().evaluate(
                quote("-1.00"),
                List.of(latest),
                latest.close(),
                true
        );

        ShortTermSupportReversalSignal signal = evaluator.evaluate(
                quote("-1.00"),
                List.of(latest),
                latest.close(),
                true,
                snapshot("0.10", "1.30"),
                momentum,
                new BigDecimal("2.00")
        );

        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.trendQualified()).isFalse();
    }

    @Test
    void rejectsDeclineBeyondTwoPercent() {
        ShortTermSupportReversalSignal signal = evaluate(
                "-2.01",
                row("10.70", "10.62", "10.72", "10.18"),
                snapshot("0.10", "1.30")
        );

        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("跌幅"));
    }

    @Test
    void reportsUnavailableShapeWhenTheCandleHasNoRange() {
        ShortTermSupportReversalSignal signal = evaluate(
                "-1.00",
                row("10.62", "10.62", "10.62", "10.62"),
                snapshot("0.10", "1.30")
        );

        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.dataGaps()).anyMatch(gap -> gap.contains("振幅"));
    }

    private ShortTermSupportReversalSignal evaluate(
            String changePercent,
            EastMoneyKLine latest,
            ShortTermTechnicalSnapshot technical
    ) {
        ShortTermMomentumQuality momentum = new ShortTermMomentumQualityEvaluator().evaluate(
                quote(changePercent),
                List.of(latest),
                latest.close(),
                true
        );
        return evaluator.evaluate(
                quote(changePercent),
                List.of(latest),
                latest.close(),
                true,
                technical,
                momentum
        );
    }

    private ShortTermTechnicalSnapshot snapshot(String ma20Slope, String volumeRatio20) {
        return new ShortTermTechnicalSnapshot(
                LocalDate.parse("2026-08-12"),
                new BigDecimal("10.60"),
                new BigDecimal("10.50"),
                new BigDecimal("10.40"),
                new BigDecimal("10.20"),
                new BigDecimal(ma20Slope),
                new BigDecimal("0.05"),
                new BigDecimal("10.85"),
                new BigDecimal("11.20"),
                new BigDecimal("-2.12"),
                new BigDecimal("8.00"),
                new BigDecimal("11.50"),
                new BigDecimal("9.20"),
                new BigDecimal("1.20"),
                new BigDecimal(volumeRatio20),
                new BigDecimal("62.00"),
                new BigDecimal("61.74"),
                new BigDecimal("2.12"),
                new BigDecimal("-7.65"),
                new BigDecimal("5.19"),
                8,
                "RIGHT_EARLY",
                ShortTermGoldenCrossSnapshot.unavailable(),
                new BigDecimal("2.80"),
                new BigDecimal("10.40")
        );
    }

    private EastMoneyKLine row(String open, String close, String high, String low) {
        return new EastMoneyKLine(
                "600041",
                LocalDate.parse("2026-08-12"),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal("260000"),
                new BigDecimal("276000000")
        );
    }

    private EastMoneyQuote quote(String changePercent) {
        Instant timestamp = Instant.parse("2026-08-12T06:50:00Z");
        return new EastMoneyQuote(
                "600041",
                "测试股份",
                "SH",
                "电力",
                new BigDecimal("10.62"),
                new BigDecimal(changePercent),
                new BigDecimal("3.00"),
                new BigDecimal("260000"),
                new BigDecimal("276000000"),
                new BigDecimal("18"),
                new BigDecimal("1.8"),
                new BigDecimal("18"),
                "东方财富",
                "https://quote.eastmoney.com/600041.html",
                timestamp,
                LocalDate.parse("2026-08-12"),
                timestamp
        );
    }
}
