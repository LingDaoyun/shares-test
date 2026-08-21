package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 东方财富涨停池单只条目，字段对应 getTopicZTPool 返回结构。
 */
public record EastMoneyLimitUpPoolEntry(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal turnoverRate,
        BigDecimal circulatingMarketValue,
        int consecutiveBoards,
        int statDays,
        int statBoards,
        BigDecimal sealFunds,
        LocalTime firstSealTime,
        LocalTime lastSealTime,
        int sealBreakCount
) {
}
