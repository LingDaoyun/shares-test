package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermScheduledSnapshot(
        String snapshotKey,
        LocalDate tradeDate,
        ShortTermSnapshotStage stage,
        ShortTermSnapshotStatus status,
        int attemptCount,
        String parameterFingerprint,
        String parametersJson,
        Instant dataCutoffAt,
        Instant startedAt,
        Instant completedAt,
        String message,
        List<String> blockedReasons,
        ShortTermReport report
) {
    @JsonProperty
    public String strategyVersion() {
        return RecommendationSource.SHORT_TERM.ruleVersion();
    }

    public ShortTermScheduledSnapshot withReport(ShortTermReport nextReport) {
        return new ShortTermScheduledSnapshot(
                snapshotKey, tradeDate, stage, status, attemptCount, parameterFingerprint,
                parametersJson, dataCutoffAt, startedAt, completedAt, message, blockedReasons,
                nextReport);
    }

    public static ShortTermScheduledSnapshot waiting(LocalDate tradeDate, String message) {
        return new ShortTermScheduledSnapshot(
                tradeDate + ":PRESELECT:WAITING", tradeDate, ShortTermSnapshotStage.PRESELECT,
                ShortTermSnapshotStatus.RUNNING, 0, "waiting", null,
                null, null, null, message, List.of(), null);
    }
}
