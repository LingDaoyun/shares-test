package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.util.List;

public record ChipVerificationResult(
        ChipVerificationStatus status,
        BigDecimal coefficient,
        BigDecimal averageCostDeviation,
        BigDecimal cost70BandOverlap,
        BigDecimal winnerRateDeviation,
        List<String> dataGaps
) {
    public ChipVerificationResult {
        status = status == null ? ChipVerificationStatus.INSUFFICIENT : status;
        coefficient = coefficient == null ? BigDecimal.ZERO : coefficient;
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
