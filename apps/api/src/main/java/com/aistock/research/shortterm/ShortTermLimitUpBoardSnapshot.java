package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 涨停看板快照：某个交易日的涨停明细、行业聚合与情绪指标。
 *
 * <p>盘中数据为拉取时点的快照，涨停、炸板数量随时变化，不代表收盘结论。
 * available=false 时 stocks/industryStats/sentiment 均为空，原因见 unavailableReason。
 */
public record ShortTermLimitUpBoardSnapshot(
        LocalDate tradeDate,
        Instant fetchedAt,
        boolean available,
        String unavailableReason,
        List<ShortTermLimitUpStock> stocks,
        List<ShortTermLimitUpIndustryStat> industryStats,
        ShortTermLimitUpSentiment sentiment,
        List<String> dataGaps
) {
    public ShortTermLimitUpBoardSnapshot {
        stocks = stocks == null ? List.of() : List.copyOf(stocks);
        industryStats = industryStats == null ? List.of() : List.copyOf(industryStats);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermLimitUpBoardSnapshot unavailable(LocalDate tradeDate, Instant fetchedAt, String reason) {
        return new ShortTermLimitUpBoardSnapshot(
                tradeDate,
                fetchedAt,
                false,
                reason == null || reason.isBlank() ? "涨停池数据不可用" : reason,
                List.of(),
                List.of(),
                null,
                List.of()
        );
    }
}
