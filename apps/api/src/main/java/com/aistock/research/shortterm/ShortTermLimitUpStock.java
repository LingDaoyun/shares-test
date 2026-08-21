package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 涨停看板单只涨停股明细，来自东方财富涨停池。
 */
public record ShortTermLimitUpStock(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal turnoverRate,
        int consecutiveBoards,
        int statDays,
        int statBoards,
        BigDecimal sealFunds,
        LocalTime firstSealTime,
        LocalTime lastSealTime,
        int sealBreakCount
) {
}
