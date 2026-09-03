package com.aistock.research.leader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LeaderAiReview(
        UUID id,
        LocalDate tradeDate,
        PotentialLeaderSnapshot.Lane lane,
        String symbol,
        String ruleVersion,
        String snapshotHash,
        String status,
        String verdict,
        String phase,
        List<String> advantages,
        List<String> counterEvidence,
        List<String> dataGaps,
        List<String> mustVerify,
        BigDecimal confidence,
        String provider,
        String model,
        String promptVersion,
        String errorSummary,
        Instant createdAt
) {
    public LeaderAiReview {
        advantages = immutable(advantages);
        counterEvidence = immutable(counterEvidence);
        dataGaps = immutable(dataGaps);
        mustVerify = immutable(mustVerify);
    }

    public record Request(String snapshotHash) {
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
