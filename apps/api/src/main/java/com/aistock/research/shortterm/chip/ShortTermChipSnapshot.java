package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashSet;

public record ShortTermChipSnapshot(
        ChipDataQuality dataQuality,
        ChipCalculationMode calculationMode,
        LocalDate localTradeDate,
        LocalDate externalTradeDate,
        BigDecimal averageCost,
        BigDecimal cost5,
        BigDecimal cost15,
        BigDecimal cost50,
        BigDecimal cost85,
        BigDecimal cost95,
        BigDecimal winnerRatePercent,
        BigDecimal overheadChipRatioPercent,
        BigDecimal cost70Low,
        BigDecimal cost70High,
        BigDecimal cost70ConcentrationPercent,
        BigDecimal cost90Low,
        BigDecimal cost90High,
        BigDecimal cost90ConcentrationPercent,
        BigDecimal distanceToAverageCostPercent,
        BigDecimal priorHighPrice,
        BigDecimal priorHighZoneResidualRatioPercent,
        BigDecimal turnoverSincePriorHighPercent,
        BigDecimal costPositionScore,
        BigDecimal concentrationScore,
        BigDecimal overheadReliefScore,
        BigDecimal priorHighDigestionScore,
        BigDecimal chipStructureScore,
        ChipVerificationStatus verificationStatus,
        String verificationLabel,
        BigDecimal verificationCoefficient,
        BigDecimal contributionScore,
        BigDecimal averageCostDeviation,
        BigDecimal cost70BandOverlap,
        BigDecimal winnerRateDeviation,
        List<ChipDistributionBucket> distributionBuckets,
        List<ChipConcentrationZone> concentrationZones,
        BigDecimal dominantPeakPrice,
        BigDecimal dominantZoneLow,
        BigDecimal dominantZoneHigh,
        BigDecimal dominantZoneChipRatioPercent,
        ChipPricePosition currentPricePosition,
        ChipConcentrationZone nearestOverheadZone,
        String modelVersion,
        List<String> dataGaps
) {
    public static final String MODEL_VERSION = "short-term-chip-v2-peaks";

    public ShortTermChipSnapshot {
        distributionBuckets = distributionBuckets == null ? List.of() : List.copyOf(distributionBuckets);
        concentrationZones = concentrationZones == null ? List.of() : List.copyOf(concentrationZones);
        dataGaps = ChipEvidenceSanitizer.sanitizeAll(dataGaps);
    }

    public ShortTermChipSnapshot withAdditionalGaps(List<String> additionalGaps) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(dataGaps);
        if (additionalGaps != null) {
            merged.addAll(additionalGaps);
        }
        return new ShortTermChipSnapshot(
                dataQuality, calculationMode, localTradeDate, externalTradeDate,
                averageCost, cost5, cost15, cost50, cost85, cost95,
                winnerRatePercent, overheadChipRatioPercent,
                cost70Low, cost70High, cost70ConcentrationPercent,
                cost90Low, cost90High, cost90ConcentrationPercent,
                distanceToAverageCostPercent, priorHighPrice,
                priorHighZoneResidualRatioPercent, turnoverSincePriorHighPercent,
                costPositionScore, concentrationScore, overheadReliefScore, priorHighDigestionScore,
                chipStructureScore, verificationStatus, verificationLabel,
                verificationCoefficient, contributionScore,
                averageCostDeviation, cost70BandOverlap, winnerRateDeviation,
                distributionBuckets, concentrationZones,
                dominantPeakPrice, dominantZoneLow, dominantZoneHigh,
                dominantZoneChipRatioPercent, currentPricePosition, nearestOverheadZone,
                modelVersion, List.copyOf(merged)
        );
    }
}
