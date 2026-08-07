package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermMarketFundDirection(
        List<ShortTermIndustryFundDirection> topInflows,
        List<ShortTermIndustryFundDirection> topOutflows,
        int coveredIndustryCount,
        int expectedIndustryCount,
        BigDecimal coverageRatio,
        LocalDate tradeDate,
        Instant fetchedAt,
        String sourceName,
        List<String> dataGaps
) {
    public ShortTermMarketFundDirection {
        topInflows = topInflows == null ? List.of() : List.copyOf(topInflows);
        topOutflows = topOutflows == null ? List.of() : List.copyOf(topOutflows);
        coverageRatio = coverageRatio == null ? BigDecimal.ZERO : coverageRatio;
        sourceName = sourceName == null || sourceName.isBlank() ? "行业资金流未返回" : sourceName;
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermMarketFundDirection unavailable(String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "行业资金流快照缺失，旧报告或数据源未返回。"
                : reason;
        return new ShortTermMarketFundDirection(
                List.of(),
                List.of(),
                0,
                0,
                BigDecimal.ZERO,
                null,
                null,
                "行业资金流未返回",
                List.of(safeReason)
        );
    }
}
