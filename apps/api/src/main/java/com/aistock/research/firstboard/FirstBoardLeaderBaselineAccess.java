package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 潜力龙头读取首板基线的公开、不可变边界。
 */
public record FirstBoardLeaderBaselineAccess(
        FirstBoardAlertStatus status,
        LocalDate tradeDate,
        LocalDate baselineDate,
        Instant publishedAt,
        Map<String, FirstBoardLeaderBaselineStock> stocks,
        FirstBoardIngestionProgress progress,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount,
        List<String> dataGaps
) {
    public FirstBoardLeaderBaselineAccess {
        stocks = stocks == null ? Map.of() : Map.copyOf(stocks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
