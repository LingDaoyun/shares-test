package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermUpperShadowScan(
        LocalDate tradeDate, Instant assessedAt, String status, String source,
        int expectedCount, int validQuoteCount, int shapeCount, int historyMissingCount,
        int matchedCount, int omittedCount, List<Candidate> candidates, List<String> dataGaps,
        String ruleDescription
) {
    public record Candidate(
            String symbol, String name, String industry, String label,
            BigDecimal latestPrice, BigDecimal changePercent, BigDecimal amount,
            BigDecimal turnoverRate, Instant marketTimestamp, BigDecimal boxRangePercent,
            BigDecimal boxNetChangePercent, BigDecimal boxHigh, BigDecimal boxLow,
            BigDecimal upperShadowRatioPercent, BigDecimal upperShadowPercent,
            List<String> evidence, List<String> counterEvidence
    ) {}
}
