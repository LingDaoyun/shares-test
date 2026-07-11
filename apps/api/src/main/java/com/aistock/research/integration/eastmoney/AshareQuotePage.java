package com.aistock.research.integration.eastmoney;

import java.util.List;

public record AshareQuotePage(
        int totalCount,
        List<EastMoneyQuote> quotes
) {
    public AshareQuotePage {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
}
