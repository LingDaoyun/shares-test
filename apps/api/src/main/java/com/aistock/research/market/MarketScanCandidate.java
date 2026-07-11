package com.aistock.research.market;

import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.RecommendationEvidenceBundle;
import com.aistock.research.valuation.ValuationContext;

import java.math.BigDecimal;
import java.util.List;

public record MarketScanCandidate(
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
        MarketScanScoreBreakdown score,
        String screeningAction,
        String screeningActionLabel,
        String reason,
        TradingAdvice todayAdvice,
        List<String> tags,
        List<String> strengths,
        List<String> risks,
        List<String> dataGaps,
        EvidenceCompleteness evidenceCompleteness,
        RecommendationEvidenceBundle evidenceBundle,
        List<MarketScanTraceStep> trace
) {
}
