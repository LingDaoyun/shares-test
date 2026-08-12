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
        ShortTermReport report,
        String reportPayloadHash,
        Instant payloadCommittedByAt
) {
    public ShortTermScheduledSnapshot(
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
        this(
                snapshotKey, tradeDate, stage, status, attemptCount, parameterFingerprint,
                parametersJson, dataCutoffAt, startedAt, completedAt, message, blockedReasons,
                report, null, null);
    }

    @JsonProperty
    public String strategyVersion() {
        return RecommendationSource.SHORT_TERM.ruleVersion();
    }

    public boolean hasCertifiedPublicationProof(Instant publicationDeadline) {
        return stage == ShortTermSnapshotStage.FINAL
                && status == ShortTermSnapshotStatus.FINAL_READY
                && report != null
                && hasSha256Length(reportPayloadHash)
                && payloadCommittedByAt != null
                && publicationDeadline != null
                && !payloadCommittedByAt.isAfter(publicationDeadline);
    }

    public ShortTermScheduledSnapshot withReport(ShortTermReport nextReport) {
        return new ShortTermScheduledSnapshot(
                snapshotKey, tradeDate, stage, status, attemptCount, parameterFingerprint,
                parametersJson, dataCutoffAt, startedAt, completedAt, message, blockedReasons,
                nextReport, reportPayloadHash, payloadCommittedByAt);
    }

    public static ShortTermScheduledSnapshot waiting(LocalDate tradeDate, String message) {
        return new ShortTermScheduledSnapshot(
                tradeDate + ":PRESELECT:WAITING", tradeDate, ShortTermSnapshotStage.PRESELECT,
                ShortTermSnapshotStatus.RUNNING, 0, "waiting", null,
                null, null, null, message, List.of(), null, null, null);
    }

    private static boolean hasSha256Length(String value) {
        return value != null && value.trim().length() == 64;
    }
}
