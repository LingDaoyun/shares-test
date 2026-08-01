package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ChipStructureScorer {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BigDecimal rankingWeight;

    public ChipStructureScorer(BigDecimal rankingWeight) {
        this.rankingWeight = rankingWeight;
    }

    public ShortTermChipSnapshot score(
            LocalChipDistribution local,
            ChipVerificationResult verification,
            ExternalChipPerformance external
    ) {
        if (local == null) {
            throw new IllegalArgumentException("本地筹码分布不能为空");
        }
        ChipVerificationResult safeVerification = verification == null
                ? new ChipVerificationResult(
                ChipVerificationStatus.INSUFFICIENT, BigDecimal.ZERO,
                null, null, null, List.of("筹码认证结果缺失"))
                : verification;
        List<String> gaps = new ArrayList<>(local.dataGaps());
        gaps.addAll(safeVerification.dataGaps());

        BigDecimal costPosition = costPosition(local.distanceToAverageCostPercent(), gaps);
        BigDecimal concentration = concentration(local.cost70ConcentrationPercent());
        BigDecimal overheadRelief = local.overheadChipRatioPercent() == null
                ? BigDecimal.ZERO
                : HUNDRED.subtract(local.overheadChipRatioPercent()).max(BigDecimal.ZERO);
        BigDecimal digestion = priorHighDigestion(
                local.priorHighZoneResidualRatioPercent(),
                local.turnoverSincePriorHighPercent());
        BigDecimal raw = costPosition.multiply(new BigDecimal("0.30"))
                .add(concentration.multiply(new BigDecimal("0.25")))
                .add(overheadRelief.multiply(new BigDecimal("0.25")))
                .add(digestion.multiply(new BigDecimal("0.20")));
        BigDecimal contribution = raw
                .multiply(safeVerification.coefficient())
                .multiply(rankingWeight);

        return new ShortTermChipSnapshot(
                local.quality(),
                local.calculationMode(),
                local.tradeDate(),
                external == null ? null : external.tradeDate(),
                local.averageCost(),
                local.cost5(),
                local.cost15(),
                local.cost50(),
                local.cost85(),
                local.cost95(),
                local.winnerRatePercent(),
                local.overheadChipRatioPercent(),
                local.cost70Low(),
                local.cost70High(),
                local.cost70ConcentrationPercent(),
                local.cost90Low(),
                local.cost90High(),
                local.cost90ConcentrationPercent(),
                local.distanceToAverageCostPercent(),
                local.priorHighPrice(),
                local.priorHighZoneResidualRatioPercent(),
                local.turnoverSincePriorHighPercent(),
                scale(costPosition),
                scale(concentration),
                scale(overheadRelief),
                scale(digestion),
                scale(raw),
                safeVerification.status(),
                safeVerification.status().label(),
                scale(safeVerification.coefficient()),
                scale(contribution),
                safeVerification.averageCostDeviation(),
                safeVerification.cost70BandOverlap(),
                safeVerification.winnerRateDeviation(),
                ShortTermChipSnapshot.MODEL_VERSION,
                List.copyOf(new LinkedHashSet<>(gaps))
        );
    }

    private BigDecimal costPosition(BigDecimal distance, List<String> gaps) {
        if (distance == null) {
            gaps.add("当前价与推算成本中枢距离缺失");
            return BigDecimal.ZERO;
        }
        if (distance.compareTo(BigDecimal.ZERO) < 0) {
            return clamp(HUNDRED.add(distance.multiply(BigDecimal.TEN)));
        }
        if (distance.compareTo(new BigDecimal("8")) <= 0) {
            return HUNDRED;
        }
        if (distance.compareTo(new BigDecimal("15")) <= 0) {
            return clamp(HUNDRED.subtract(
                    distance.subtract(new BigDecimal("8"))
                            .multiply(HUNDRED)
                            .divide(new BigDecimal("7"), 8, RoundingMode.HALF_UP)));
        }
        gaps.add("当前价明显远离推算成本中枢，存在追高风险");
        return BigDecimal.ZERO;
    }

    private BigDecimal concentration(BigDecimal widthPercent) {
        if (widthPercent == null) {
            return BigDecimal.ZERO;
        }
        return clamp(HUNDRED.subtract(widthPercent.multiply(new BigDecimal("5"))));
    }

    private BigDecimal priorHighDigestion(BigDecimal residualPercent, BigDecimal turnoverSinceHigh) {
        if (residualPercent == null || turnoverSinceHigh == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal residualRelief = HUNDRED.subtract(residualPercent).max(BigDecimal.ZERO);
        BigDecimal turnoverScore = turnoverSinceHigh.max(BigDecimal.ZERO).min(HUNDRED);
        return clamp(residualRelief.multiply(new BigDecimal("0.60"))
                .add(turnoverScore.multiply(new BigDecimal("0.40"))));
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(HUNDRED);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
