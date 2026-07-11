package com.aistock.research.universe;

import com.aistock.research.valuation.ValuationContext;

import java.math.BigDecimal;
import java.util.List;

public record UniversalScreenCandidate(
        int rank,
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
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
