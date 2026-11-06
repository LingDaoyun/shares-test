package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MarketThemeDetail(
        LocalDate tradeDate, Instant fetchedAt, MarketTheme theme, String membersSource,
        boolean membershipComplete, Integer memberCount, Integer limitUpCount, Integer maxConsecutiveBoards,
        Instant limitUpFetchedAt,
        List<ShortTermLimitUpStock> limitUpStocks, List<Member> members, List<String> dataGaps
) {
    public MarketThemeDetail {
        limitUpStocks = List.copyOf(limitUpStocks);
        members = List.copyOf(members);
        dataGaps = List.copyOf(dataGaps);
    }

    public record Member(String symbol, String name, BigDecimal latestPrice, BigDecimal changePercent,
                         BigDecimal amount, BigDecimal turnoverRate, Instant marketTimestamp) {}
}
