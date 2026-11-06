package com.aistock.research.integration.news;

import java.util.List;

public record IndustryNewsFetchResult(
        List<IndustryNewsItem> items,
        List<String> dataGaps
) {
    public IndustryNewsFetchResult {
        items = items == null ? List.of() : List.copyOf(items);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
