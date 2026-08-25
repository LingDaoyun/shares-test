package com.aistock.research.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 高股息看板快照：全市场最新完整年报口径下，股息率超过阈值的股票明细、行业聚合与样本概况。
 *
 * <p>股息率取东方财富年报指标的最新股息率（ZXGXL），随行情快照更新但不做盘中实时重算；
 * available=false 时 stocks/industryStats 为空，原因见 unavailableReason；
 * 实时行情补充失败只记入 dataGaps，不影响股息率排名。
 */
public record HighDividendBoardSnapshot(
        int reportYear,
        Instant generatedAt,
        boolean available,
        String unavailableReason,
        BigDecimal thresholdYield,
        int totalCount,
        List<HighDividendIndustryStat> industryStats,
        List<HighDividendStock> stocks,
        List<String> dataGaps
) {
    public HighDividendBoardSnapshot {
        industryStats = industryStats == null ? List.of() : List.copyOf(industryStats);
        stocks = stocks == null ? List.of() : List.copyOf(stocks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static HighDividendBoardSnapshot unavailable(
            int reportYear,
            Instant generatedAt,
            BigDecimal thresholdYield,
            String reason
    ) {
        return new HighDividendBoardSnapshot(
                reportYear,
                generatedAt,
                false,
                reason == null || reason.isBlank() ? "高股息数据不可用" : reason,
                thresholdYield,
                0,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
