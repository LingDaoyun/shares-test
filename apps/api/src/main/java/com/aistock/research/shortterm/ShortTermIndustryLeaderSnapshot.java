package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermIndustryLeaderSnapshot(
        String code,
        String name,
        String flowDirection,
        List<ShortTermIndustryLeaderStock> stocks,
        LocalDate tradeDate,
        Instant fetchedAt,
        String sourceName,
        List<String> dataGaps
) {
    public ShortTermIndustryLeaderSnapshot {
        flowDirection = flowDirection == null || flowDirection.isBlank() ? "INFLOW" : flowDirection;
        stocks = stocks == null ? List.of() : List.copyOf(stocks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermIndustryLeaderSnapshot unavailable(String code, String name, String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "板块个股资金流暂不可用。"
                : reason;
        return new ShortTermIndustryLeaderSnapshot(
                code,
                name,
                "INFLOW",
                List.of(),
                null,
                null,
                "板块个股资金流未返回",
                List.of(safeReason)
        );
    }
}
