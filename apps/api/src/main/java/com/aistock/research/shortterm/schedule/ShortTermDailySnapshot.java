package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermUpperShadowScan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermDailySnapshot(
        LocalDate tradeDate, Instant scheduledAt, Instant startedAt, Instant finishedAt,
        String status, String message, ShortTermReport report,
        ShortTermUpperShadowScan upperShadow, List<String> dataGaps, String runId
) {
    public ShortTermDailySnapshot {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        if (runId == null || runId.isBlank()) {
            runId = tradeDate == null || startedAt == null ? null : "daily:" + tradeDate + ":" + startedAt;
        }
    }

    public ShortTermDailySnapshot(LocalDate tradeDate, Instant scheduledAt, Instant startedAt, Instant finishedAt,
                                  String status, String message, ShortTermReport report,
                                  ShortTermUpperShadowScan upperShadow, List<String> dataGaps) {
        this(tradeDate, scheduledAt, startedAt, finishedAt, status, message, report, upperShadow, dataGaps, null);
    }
}
