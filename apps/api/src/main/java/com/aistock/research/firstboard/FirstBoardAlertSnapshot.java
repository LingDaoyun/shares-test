package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FirstBoardAlertSnapshot(
        FirstBoardAlertStatus status,
        LocalDate tradeDate,
        LocalDate baselineDate,
        Instant generatedAt,
        Instant quoteFetchedAt,
        boolean autoRefreshAllowed,
        String ruleVersion,
        String phase,
        int progressCompleted,
        int progressTotal,
        int progressFailed,
        Instant baselinePublishedAt,
        Instant nextRetryAt,
        FirstBoardAlertCoverage coverage,
        List<FirstBoardAlertCandidate> latentCandidates,
        List<FirstBoardAlertCandidate> previousLimitUpCandidates,
        List<String> dataGaps
) {
    public FirstBoardAlertSnapshot {
        latentCandidates = latentCandidates == null ? List.of() : List.copyOf(latentCandidates);
        previousLimitUpCandidates = previousLimitUpCandidates == null
                ? List.of() : List.copyOf(previousLimitUpCandidates);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
