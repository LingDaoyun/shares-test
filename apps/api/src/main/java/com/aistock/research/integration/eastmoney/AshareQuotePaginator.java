package com.aistock.research.integration.eastmoney;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

public final class AshareQuotePaginator {

    private static final int MAX_PAGES = 160;

    private AshareQuotePaginator() {
    }

    public static List<EastMoneyQuote> collect(int limit, IntFunction<AshareQuotePage> pageFetcher) {
        int target = Math.max(1, limit);
        Map<String, EastMoneyQuote> quotes = new LinkedHashMap<>();
        int expectedPages = MAX_PAGES;

        for (int pageNumber = 1; pageNumber <= Math.min(expectedPages, MAX_PAGES); pageNumber++) {
            AshareQuotePage page = pageFetcher.apply(pageNumber);
            if (page == null || page.quotes().isEmpty()) {
                break;
            }
            page.quotes().stream()
                    .filter(quote -> quote.symbol() != null && !quote.symbol().isBlank())
                    .forEach(quote -> quotes.putIfAbsent(quote.symbol(), quote));

            int effectivePageSize = page.quotes().size();
            int availableTarget = page.totalCount() > 0
                    ? Math.min(target, page.totalCount())
                    : target;
            expectedPages = Math.min(
                    MAX_PAGES,
                    (int) Math.ceil(availableTarget / (double) effectivePageSize)
            );
            if (quotes.size() >= availableTarget) {
                break;
            }
        }

        return quotes.values().stream().limit(target).toList();
    }
}
