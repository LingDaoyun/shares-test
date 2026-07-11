package com.aistock.research.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

public record WatchlistEntry(
        String symbol,
        String companyName,
        String note,
        String lastActionLabel,
        BigDecimal lastDecisionScore,
        Instant lastAnalyzedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
