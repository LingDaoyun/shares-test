package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;

/** 独立于选股扫描的全 A 股涨跌统计；仅计入统计日有价格和涨跌幅的报价。 */
public record ShortTermMarketBreadth(
        LocalDate tradeDate,
        Instant fetchedAt,
        int advancing,
        int declining,
        int unchanged,
        int excludedCount,
        int totalCount,
        String source
) {
}
