package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShortTermCatchUpCandidate(
        String symbol, String name, BigDecimal latestPrice, BigDecimal changePercent,
        BigDecimal amount, BigDecimal turnoverRate, Instant marketTimestamp,
        BigDecimal return5, BigDecimal returnPercentile, BigDecimal returnGapToLeader,
        boolean middle, String historyStatus, List<String> evidence,
        List<String> contraryEvidence, List<String> dataGaps
) {
    public ShortTermCatchUpCandidate {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        contraryEvidence = contraryEvidence == null ? List.of() : List.copyOf(contraryEvidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
