package com.aistock.research.news;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FinancialNewsSnapshot(String status, Instant startedAt, Instant completedAt,
                                    List<FinancialNewsItem> items, List<NewsSourceStatus> sources,
                                    Map<String, NewsAnalysis> analyses, List<String> dataGaps) {
    public FinancialNewsSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        sources = sources == null ? List.of() : List.copyOf(sources);
        analyses = analyses == null ? Map.of() : Map.copyOf(analyses);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static FinancialNewsSnapshot idle() {
        return new FinancialNewsSnapshot("IDLE", null, null, List.of(), List.of(), Map.of(), List.of());
    }
}
