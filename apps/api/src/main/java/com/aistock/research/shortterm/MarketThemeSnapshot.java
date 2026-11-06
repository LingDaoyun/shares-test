package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MarketThemeSnapshot(
        LocalDate tradeDate, Instant fetchedAt, String status, String source,
        int totalConceptCount, int excludedStructuralCount, int validQuoteCount,
        List<MarketTheme> themes, List<String> dataGaps
) {
    public MarketThemeSnapshot {
        themes = List.copyOf(themes);
        dataGaps = List.copyOf(dataGaps);
    }
}
