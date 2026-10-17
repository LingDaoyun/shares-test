package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermCatchUpFollowUpResult(
        String id, LocalDate tradeDate, LocalDate followUpDate, String symbol, String leaderSymbol,
        BigDecimal observedPrice, String status, BigDecimal latestPrice, LocalDate quoteTradeDate,
        Instant marketTimestamp, BigDecimal priceChangePercent, String explanation, List<String> dataGaps
) {
    public ShortTermCatchUpFollowUpResult {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
