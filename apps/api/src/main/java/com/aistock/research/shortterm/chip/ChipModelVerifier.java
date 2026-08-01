package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public class ChipModelVerifier {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BigDecimal maxAverageCostDeviation;
    private final BigDecimal minCostBandOverlap;
    private final BigDecimal maxWinnerRateDeviation;
    private final BigDecimal singleSourceCoefficient;

    public ChipModelVerifier(
            BigDecimal maxAverageCostDeviation,
            BigDecimal minCostBandOverlap,
            BigDecimal maxWinnerRateDeviation,
            BigDecimal singleSourceCoefficient
    ) {
        this.maxAverageCostDeviation = maxAverageCostDeviation;
        this.minCostBandOverlap = minCostBandOverlap;
        this.maxWinnerRateDeviation = maxWinnerRateDeviation;
        this.singleSourceCoefficient = singleSourceCoefficient;
    }

    public ChipVerificationResult verify(
            LocalChipDistribution local,
            ExternalChipPerformance external,
            LocalDate expectedTradeDate
    ) {
        if (local == null || local.quality() != ChipDataQuality.VALID) {
            return result(ChipVerificationStatus.INSUFFICIENT, BigDecimal.ZERO,
                    null, null, null, "本地筹码历史数据不足");
        }
        if (expectedTradeDate == null || !expectedTradeDate.equals(local.tradeDate())) {
            return result(ChipVerificationStatus.STALE, BigDecimal.ZERO,
                    null, null, null, "本地筹码基准不是最近完整交易日");
        }
        if (external == null || !external.completeForVerification()) {
            String gap = external == null ? "外部筹码认证不可用" : "外部筹码认证字段不完整";
            return result(ChipVerificationStatus.SINGLE_SOURCE, singleSourceCoefficient,
                    null, null, null, gap);
        }
        if (!expectedTradeDate.equals(external.tradeDate())) {
            return result(ChipVerificationStatus.STALE, BigDecimal.ZERO,
                    null, null, null, "外部筹码认证不是最近完整交易日");
        }

        BigDecimal averageDeviation = local.averageCost().subtract(external.averageCost()).abs()
                .divide(external.averageCost(), 8, RoundingMode.HALF_UP);
        BigDecimal overlap = bandOverlap(
                local.cost15(), local.cost85(), external.cost15(), external.cost85());
        BigDecimal winnerDeviation = local.winnerRatePercent().subtract(external.winnerRatePercent()).abs()
                .divide(HUNDRED, 8, RoundingMode.HALF_UP);
        boolean verified = averageDeviation.compareTo(maxAverageCostDeviation) <= 0
                && overlap.compareTo(minCostBandOverlap) >= 0
                && winnerDeviation.compareTo(maxWinnerRateDeviation) <= 0;
        if (verified) {
            return new ChipVerificationResult(
                    ChipVerificationStatus.VERIFIED,
                    BigDecimal.ONE,
                    scale(averageDeviation),
                    scale(overlap),
                    scale(winnerDeviation),
                    List.of()
            );
        }
        return new ChipVerificationResult(
                ChipVerificationStatus.CONFLICT,
                BigDecimal.ZERO,
                scale(averageDeviation),
                scale(overlap),
                scale(winnerDeviation),
                List.of("本地与外部筹码模型超出容差")
        );
    }

    private BigDecimal bandOverlap(
            BigDecimal localLow,
            BigDecimal localHigh,
            BigDecimal externalLow,
            BigDecimal externalHigh
    ) {
        BigDecimal intersectionLow = localLow.max(externalLow);
        BigDecimal intersectionHigh = localHigh.min(externalHigh);
        BigDecimal intersection = intersectionHigh.subtract(intersectionLow).max(BigDecimal.ZERO);
        BigDecimal union = localHigh.max(externalHigh).subtract(localLow.min(externalLow));
        if (union.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        return intersection.divide(union, 8, RoundingMode.HALF_UP);
    }

    private ChipVerificationResult result(
            ChipVerificationStatus status,
            BigDecimal coefficient,
            BigDecimal averageDeviation,
            BigDecimal overlap,
            BigDecimal winnerDeviation,
            String gap
    ) {
        return new ChipVerificationResult(
                status, coefficient, averageDeviation, overlap, winnerDeviation, List.of(gap));
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }
}
