package com.aistock.research.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 外围市场单条行情（指数或行业 ETF 代理）。changePercent 为百分数口径（0.98 表示 +0.98%）。
 */
public record OverseasIndexQuote(
        String code,
        String name,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal changeValue,
        String currency,
        Instant quoteTime
) {
}
