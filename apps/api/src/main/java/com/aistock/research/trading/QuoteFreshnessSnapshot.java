package com.aistock.research.trading;

import java.time.Instant;
import java.time.LocalDate;

public record QuoteFreshnessSnapshot(
        String status,
        String statusLabel,
        boolean realtimeSession,
        boolean blocksRealtimeDecision,
        LocalDate tradeDate,
        Instant marketTimestamp,
        Long ageSeconds,
        String reason
) {
}
