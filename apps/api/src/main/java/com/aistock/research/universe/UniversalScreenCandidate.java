package com.aistock.research.universe;

import com.aistock.research.valuation.ValuationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UniversalScreenCandidate(
        int rank,
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
        String sourceName,
        String quoteUrl,
        Instant fetchedAt,
        LocalDate tradeDate,
        Instant marketTimestamp,
        BigDecimal changePercent,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal amount,
        ValuationContext valuationContext,
        UniversalScreenScore score,
        String bucket,
        String action,
        String actionLabel,
        String reason,
        List<String> strengths,
        List<String> risks,
        List<String> dataGaps,
        List<UniversalScreenTraceStep> trace
) {
}
