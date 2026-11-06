package com.aistock.research.news;

import java.util.List;

public record FinancialNewsFetchResult(List<FinancialNewsItem> items, List<NewsSourceStatus> sources) {
    public FinancialNewsFetchResult {
        items = items == null ? List.of() : List.copyOf(items);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
