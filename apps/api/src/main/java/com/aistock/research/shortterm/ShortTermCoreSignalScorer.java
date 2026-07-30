package com.aistock.research.shortterm;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ShortTermCoreSignalScorer {

    private static final BigDecimal GOLDEN_CROSS_WEIGHT = new BigDecimal("0.45");
    private static final BigDecimal VOLUME_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal TURNOVER_WEIGHT = new BigDecimal("0.15");
    private static final BigDecimal CLOSE_STRENGTH_WEIGHT = new BigDecimal("0.10");

    public ShortTermCoreSignalScore score(
            ShortTermGoldenCrossSnapshot goldenCross,
            BigDecimal volumeRatio20,
            BigDecimal changePercent,
            ShortTermMomentumQuality momentumQuality
    ) {
        return score(
                goldenCross,
                volumeRatio20,
                changePercent,
                momentumQuality,
                new BigDecimal("1.20")
        );
    }

    public ShortTermCoreSignalScore score(
            ShortTermGoldenCrossSnapshot goldenCross,
            BigDecimal volumeRatio20,
            BigDecimal changePercent,
            ShortTermMomentumQuality momentumQuality,
            BigDecimal minVolumeRatio
    ) {
        BigDecimal goldenCrossScore = goldenCrossScore(goldenCross);
        BigDecimal volumeScore = volumeScore(volumeRatio20, changePercent, minVolumeRatio);
        ShortTermMomentumQuality quality = momentumQuality == null
                ? ShortTermMomentumQuality.unavailable()
                : momentumQuality;
        BigDecimal turnoverScore = fallback(quality.turnoverScore(), "45");
        BigDecimal closeStrengthScore = fallback(quality.closeStrengthScore(), "50");
        BigDecimal finalScore = goldenCrossScore.multiply(GOLDEN_CROSS_WEIGHT)
                .add(volumeScore.multiply(VOLUME_WEIGHT))
                .add(turnoverScore.multiply(TURNOVER_WEIGHT))
                .add(closeStrengthScore.multiply(CLOSE_STRENGTH_WEIGHT));
        return new ShortTermCoreSignalScore(
                scale(goldenCrossScore),
                scale(volumeScore),
                scale(turnoverScore),
                scale(closeStrengthScore),
                scale(finalScore)
        );
    }

    BigDecimal goldenCrossScore(ShortTermGoldenCrossSnapshot goldenCross) {
        if (goldenCross == null || goldenCross.state() == null) {
            return new BigDecimal("30");
        }
        if (goldenCross.confirmedRecent()) {
            if ("WIDENING".equals(goldenCross.spreadTrend())) {
                return new BigDecimal("100");
            }
            if ("NARROWING".equals(goldenCross.spreadTrend())) {
                return new BigDecimal("88");
            }
            return new BigDecimal("94");
        }
        return switch (goldenCross.state()) {
            case "ESTABLISHED" -> goldenCross.tradingDaysSinceCross() != null
                    && goldenCross.tradingDaysSinceCross() <= 5
                    ? new BigDecimal("76")
                    : new BigDecimal("52");
            case "FORMING" -> new BigDecimal("68");
            case "APPROACHING" -> new BigDecimal("62");
            case "CONFIRMED" -> new BigDecimal("72");
            case "NONE" -> new BigDecimal("30");
            default -> new BigDecimal("35");
        };
    }

    BigDecimal volumeScore(BigDecimal volumeRatio20, BigDecimal changePercent) {
        return volumeScore(volumeRatio20, changePercent, new BigDecimal("1.20"));
    }

    BigDecimal volumeScore(
            BigDecimal volumeRatio20,
            BigDecimal changePercent,
            BigDecimal minVolumeRatio
    ) {
        if (volumeRatio20 == null) {
            return new BigDecimal("45");
        }
        if (changePercent == null || changePercent.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("35");
        }
        BigDecimal configuredMinimum = minVolumeRatio == null
                ? new BigDecimal("1.20")
                : minVolumeRatio.max(BigDecimal.ONE).min(new BigDecimal("3.20"));
        if (volumeRatio20.compareTo(configuredMinimum) >= 0
                && volumeRatio20.compareTo(new BigDecimal("2.80")) <= 0) {
            return new BigDecimal("100");
        }
        if (volumeRatio20.compareTo(BigDecimal.ONE) >= 0
                && volumeRatio20.compareTo(configuredMinimum) < 0) {
            return new BigDecimal("65");
        }
        if (volumeRatio20.compareTo(new BigDecimal("2.80")) > 0
                && volumeRatio20.compareTo(new BigDecimal("3.20")) <= 0) {
            return new BigDecimal("78");
        }
        if (volumeRatio20.compareTo(BigDecimal.ONE) < 0) {
            return new BigDecimal("40");
        }
        if (volumeRatio20.compareTo(new BigDecimal("4.20")) <= 0) {
            return new BigDecimal("50");
        }
        return new BigDecimal("30");
    }

    private BigDecimal fallback(BigDecimal value, String fallback) {
        return value == null ? new BigDecimal(fallback) : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
