package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;

public record FirstBoardIngestionProgress(
        LocalDate tradeDate,
        FirstBoardMarketDayStatus status,
        String phase,
        int completed,
        int total,
        int failed,
        Instant nextRetryAt,
        String errorSummary
) {
    static FirstBoardIngestionProgress from(FirstBoardMarketDay day) {
        String phase = switch (day.status()) {
            case IMPORTING -> "IMPORTING_BARS";
            case READY -> "READY";
            case FAILED -> "FAILED";
        };
        return new FirstBoardIngestionProgress(
                day.tradeDate(), day.status(), phase, day.writtenSymbolCount(), day.expectedSymbolCount(),
                day.failedSymbolCount(), day.nextRetryAt(), day.errorSummary());
    }
}
