package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 涨停看板快照：某个交易日的涨停明细、行业聚合、情绪指标与大盘量能。
 *
 * <p>盘中数据为拉取时点的快照，涨停、炸板数量随时变化，不代表收盘结论。
 * available=false 时 stocks/industryStats/sentiment 均为空，原因见 unavailableReason；
 * limitDownAvailable 独立表示跌停池可用性，跌停池可用但没有记录时 limitDownStocks 为空列表；
 * marketTurnover 独立容错，为空表示量能数据缺口，见 dataGaps。
 */
public record ShortTermLimitUpBoardSnapshot(
        LocalDate tradeDate,
        Instant fetchedAt,
        boolean available,
        String unavailableReason,
        List<ShortTermLimitUpStock> stocks,
        boolean brokenBoardAvailable,
        List<ShortTermBrokenBoardStock> brokenStocks,
        List<ShortTermLimitUpIndustryStat> industryStats,
        ShortTermLimitUpSentiment sentiment,
        ShortTermMarketTurnover marketTurnover,
        List<String> dataGaps,
        boolean limitDownAvailable,
        List<ShortTermLimitDownStock> limitDownStocks
) {
    public ShortTermLimitUpBoardSnapshot {
        stocks = stocks == null ? List.of() : List.copyOf(stocks);
        brokenStocks = brokenStocks == null ? List.of() : List.copyOf(brokenStocks);
        industryStats = industryStats == null ? List.of() : List.copyOf(industryStats);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        limitDownStocks = limitDownStocks == null ? List.of() : List.copyOf(limitDownStocks);
    }

    public ShortTermLimitUpBoardSnapshot(LocalDate tradeDate, Instant fetchedAt, boolean available, String unavailableReason,
                                         List<ShortTermLimitUpStock> stocks, boolean brokenBoardAvailable,
                                         List<ShortTermBrokenBoardStock> brokenStocks, List<ShortTermLimitUpIndustryStat> industryStats,
                                         ShortTermLimitUpSentiment sentiment, ShortTermMarketTurnover marketTurnover, List<String> dataGaps) {
        this(tradeDate, fetchedAt, available, unavailableReason, stocks, brokenBoardAvailable, brokenStocks, industryStats,
                sentiment, marketTurnover, dataGaps, false, List.of());
    }

    public static ShortTermLimitUpBoardSnapshot unavailable(LocalDate tradeDate, Instant fetchedAt, String reason) {
        return unavailable(tradeDate, fetchedAt, reason, List.of(), false, List.of());
    }

    static ShortTermLimitUpBoardSnapshot unavailable(LocalDate tradeDate, Instant fetchedAt, String reason, List<String> dataGaps,
                                                     boolean limitDownAvailable, List<ShortTermLimitDownStock> limitDownStocks) {
        return new ShortTermLimitUpBoardSnapshot(
                tradeDate,
                fetchedAt,
                false,
                reason == null || reason.isBlank() ? "涨停池数据不可用" : reason,
                List.of(),
                false,
                List.of(),
                List.of(),
                null,
                null,
                dataGaps,
                limitDownAvailable,
                limitDownStocks
        );
    }
}
