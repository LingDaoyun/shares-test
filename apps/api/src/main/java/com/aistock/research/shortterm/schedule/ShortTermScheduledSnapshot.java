package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermScheduledSnapshot(
        String snapshotKey,
        LocalDate tradeDate,
        ShortTermSnapshotStage stage,
        ShortTermSnapshotStatus status,
        String parameterFingerprint,
        Instant dataCutoffAt,
        Instant startedAt,
        Instant completedAt,
        String message,
        List<String> blockedReasons,
        ShortTermReport report
) {
    public static ShortTermScheduledSnapshot waiting(LocalDate tradeDate, String message) {
        return new ShortTermScheduledSnapshot(
                tradeDate + ":PRESELECT:WAITING", tradeDate, ShortTermSnapshotStage.PRESELECT,
                ShortTermSnapshotStatus.RUNNING, "waiting", null, null, null, message, List.of(), null);
    }
}
