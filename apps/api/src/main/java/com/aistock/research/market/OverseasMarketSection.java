package com.aistock.research.market;

import java.util.List;

/**
 * 单个外围市场块（美股/日本/韩国）：主要指数 + 行业涨跌。
 * industries 为行业 ETF 代理口径；status 按交易所当地时段估算（OPEN/CLOSED）。
 */
public record OverseasMarketSection(
        String market,
        String marketLabel,
        String status,
        List<OverseasIndexQuote> indices,
        List<OverseasIndexQuote> industries,
        String unavailableReason
) {
    public OverseasMarketSection {
        indices = indices == null ? List.of() : List.copyOf(indices);
        industries = industries == null ? List.of() : List.copyOf(industries);
    }
}
