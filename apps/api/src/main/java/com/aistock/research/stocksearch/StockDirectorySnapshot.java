package com.aistock.research.stocksearch;

import java.time.Instant;
import java.util.List;

public record StockDirectorySnapshot(
        List<Stock> stocks,
        int expectedCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {
    public StockDirectorySnapshot {
        stocks = List.copyOf(stocks);
    }

    public record Stock(String symbol, String name, String market) {}
}
