package com.aistock.research.shortterm.chip;

import java.time.Instant;
import java.time.LocalDate;

public record ShortTermChipVerificationEvidence(
        String symbol,
        LocalDate tradeDate,
        String modelVersion,
        ShortTermChipSnapshot snapshot,
        ExternalChipPerformance external,
        Instant dataCutoffAt,
        Instant observedAt,
        String errorSummary
) {
}
