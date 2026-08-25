package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 全球市场行情快照（东财 secid 体系：100.=全球指数，107.=美交所 ETF）。
 * changePercent 为百分数口径（0.98 表示 +0.98%），与 EastMoneyQuote 一致。
 */
public record EastMoneyGlobalQuote(
        String code,
        String name,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal changeValue,
        BigDecimal prevClose,
        int marketCode,
        Instant quoteTime
) {
}
