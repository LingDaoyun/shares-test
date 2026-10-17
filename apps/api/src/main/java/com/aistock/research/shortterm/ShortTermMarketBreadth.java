package com.aistock.research.shortterm;

import java.time.Instant;
import java.time.LocalDate;

/** 独立于选股扫描的全 A 股涨跌统计；源时间范围仅覆盖实际计入报价，不代表收盘确认。 */
public record ShortTermMarketBreadth(
        LocalDate tradeDate,
        Instant fetchedAt,
        Instant marketTimestampFrom,
        Instant marketTimestampTo,
        int missingTimestampCount,
        int advancing,
        int declining,
        int unchanged,
        int excludedCount,
        int totalCount,
        String source
) {
}
