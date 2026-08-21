package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 东方财富炸板池单只条目，字段对应 getTopicZBPool 返回结构。
 */
public record EastMoneyBrokenBoardPoolEntry(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal limitUpPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        LocalTime firstSealTime,
        int sealBreakCount
) {
}
