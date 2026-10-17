package com.aistock.research.shortterm;

import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermScanJobStatus(
        String jobId,
        String status,
        LocalDate tradeDate,
        ShortTermSnapshotStatus resultStatus,
        List<String> blockedReasons,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String message,
        ShortTermReport report,
        ShortTermUpperShadowScan upperShadow,
        List<String> dataGaps
) {
    public ShortTermScanJobStatus(String jobId, String status, LocalDate tradeDate,
                                 ShortTermSnapshotStatus resultStatus, List<String> blockedReasons,
                                 Instant createdAt, Instant startedAt, Instant finishedAt,
                                 String message, ShortTermReport report) {
        this(jobId, status, tradeDate, resultStatus, blockedReasons, createdAt, startedAt, finishedAt,
                message, report, null, List.of());
    }

    @JsonProperty
    public String strategyVersion() {
        return RecommendationSource.SHORT_TERM.ruleVersion();
    }
}
