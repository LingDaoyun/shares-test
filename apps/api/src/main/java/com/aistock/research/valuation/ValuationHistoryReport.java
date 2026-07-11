package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ValuationHistoryReport(
        String symbol,
        String companyName,
        String status,
        String statusLabel,
        int sampleCount,
        BigDecimal currentPe,
        BigDecimal currentPb,
        BigDecimal pePercentile,
        BigDecimal pbPercentile,
        BigDecimal averagePe,
        BigDecimal averagePb,
        BigDecimal minPe,
        BigDecimal maxPe,
        BigDecimal minPb,
        BigDecimal maxPb,
        PeerValuationReport peerValuation,
        List<ValuationHistoryPoint> points,
        List<String> conclusions,
        List<String> dataGaps,
        Instant generatedAt
) {
}
