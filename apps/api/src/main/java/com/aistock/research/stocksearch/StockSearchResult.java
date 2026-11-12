package com.aistock.research.stocksearch;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;

import java.time.Instant;
import java.util.List;

public record StockSearchResult(
        String keyword,
        List<EastMoneyQuote> stocks,
        int matchedCount,
        int candidateCount,
        int unverifiedCount,
        int universeCount,
        int expectedCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {}
