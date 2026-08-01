package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LocalChipDistribution(
        ChipDataQuality quality,
        ChipCalculationMode calculationMode,
        LocalDate tradeDate,
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
        BigDecimal turnoverCoveragePercent,
        int validBars,
        List<String> dataGaps
) {
    public LocalChipDistribution {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static LocalChipDistribution insufficient(
            ChipCalculationMode mode,
            LocalDate tradeDate,
            int validBars,
            BigDecimal turnoverCoveragePercent,
            List<String> dataGaps
    ) {
        return new LocalChipDistribution(
                ChipDataQuality.INSUFFICIENT,
                mode,
                tradeDate,
                null, null, null, null, null, null,
                null, null,
                null, null, null,
                null, null, null,
                null, null, null, null,
                turnoverCoveragePercent,
                validBars,
                dataGaps
        );
    }
}
