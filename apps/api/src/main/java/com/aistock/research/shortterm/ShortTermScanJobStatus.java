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
        ShortTermReport report
) {
    @JsonProperty
    public String strategyVersion() {
        return RecommendationSource.SHORT_TERM.ruleVersion();
    }
}
