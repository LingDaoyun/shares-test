package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermLimitUpIndustrySnapshot(
        LocalDate tradeDate, String industry, Instant fetchedAt, int limitUpCount,
        List<ShortTermLimitUpStock> limitUpStocks, List<ShortTermIndustryRisingStock> risingStocks,
        boolean risingAvailable, List<String> dataGaps, String quoteSource
) {
    public ShortTermLimitUpIndustrySnapshot {
        limitUpStocks = List.copyOf(limitUpStocks);
        risingStocks = List.copyOf(risingStocks);
        dataGaps = List.copyOf(dataGaps);
    }
}
