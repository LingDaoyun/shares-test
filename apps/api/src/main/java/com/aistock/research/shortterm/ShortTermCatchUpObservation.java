package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 浏览器保存的首次观察价；不是成交记录，不由后续快照覆盖。 */
public record ShortTermCatchUpObservation(
        String id, LocalDate tradeDate, String symbol, String leaderSymbol, BigDecimal observedPrice
) {
}
