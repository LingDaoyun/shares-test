package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

record FirstBoardBaselineSnapshot(
        UUID runId,
        LocalDate tradeDate,
        LocalDate baselineDate,
        String ruleVersion,
        String barDataVersion,
        Instant generatedAt,
        Map<String, FirstBoardBaseline> stocks,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount
) {
    FirstBoardBaselineSnapshot {
        stocks = stocks == null ? Map.of() : Map.copyOf(stocks);
    }

    FirstBoardBaselineSnapshot(
            LocalDate tradeDate,
            LocalDate baselineDate,
            Instant generatedAt,
            Map<String, FirstBoardBaseline> stocks,
            int universeCount,
            int supportedCount,
            int excludedNameCount,
            int insufficientHistoryCount
    ) {
        this(null, tradeDate, baselineDate, null, null, generatedAt, stocks, universeCount,
                supportedCount, excludedNameCount, insufficientHistoryCount);
    }
}
