package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;

public record FirstBoardMarketDay(
        LocalDate tradeDate,
        LocalDate sourceTradeDate,
        FirstBoardMarketDayStatus status,
        int expectedSymbolCount,
        int writtenSymbolCount,
        int failedSymbolCount,
        boolean limitUpPoolComplete,
        String barDataVersion,
        Instant startedAt,
        Instant completedAt,
        Instant nextRetryAt,
        String errorSummary
) {
}
