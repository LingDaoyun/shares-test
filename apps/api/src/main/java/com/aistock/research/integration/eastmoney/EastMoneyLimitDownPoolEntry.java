package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 东方财富跌停池单只条目，字段对应 getTopicDTPool 返回结构。
 */
public record EastMoneyLimitDownPoolEntry(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal sealFunds,
        LocalTime lastSealTime,
        int openCount
) {
}
