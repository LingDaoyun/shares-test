package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ExternalChipPerformance(
        String symbol,
        LocalDate tradeDate,
        BigDecimal cost5,
        BigDecimal cost15,
        BigDecimal cost50,
        BigDecimal cost85,
        BigDecimal cost95,
        BigDecimal averageCost,
        BigDecimal winnerRatePercent,
        String sourceName,
        Instant observedAt
) {
    public boolean completeForVerification() {
        return tradeDate != null
                && positive(cost15)
                && positive(cost85)
                && positive(averageCost)
                && winnerRatePercent != null
                && winnerRatePercent.compareTo(BigDecimal.ZERO) >= 0
                && winnerRatePercent.compareTo(new BigDecimal("100")) <= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
