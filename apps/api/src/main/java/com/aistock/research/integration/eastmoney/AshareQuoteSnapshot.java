package com.aistock.research.integration.eastmoney;

import java.time.Instant;
import java.util.List;

public record AshareQuoteSnapshot(
        List<EastMoneyQuote> quotes,
        int requestedCount,
        int expectedCount,
        int fetchedCount,
        int missingCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {
    public AshareQuoteSnapshot {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
}
