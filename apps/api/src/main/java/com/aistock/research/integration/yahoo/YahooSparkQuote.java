package com.aistock.research.integration.yahoo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Yahoo Finance spark 行情：以最近两根日线收盘价推算涨跌幅。
 * changePercent 为百分数口径（0.98 表示 +0.98%），与 EastMoneyQuote 一致。
 */
public record YahooSparkQuote(
        String symbol,
        BigDecimal lastClose,
        BigDecimal changePercent,
        Instant lastTradeTime
) {
}
