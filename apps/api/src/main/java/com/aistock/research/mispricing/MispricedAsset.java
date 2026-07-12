package com.aistock.research.mispricing;

import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.RecommendationEvidenceBundle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MispricedAsset(
        int rank,
        String symbol,
        String name,
        String assetGroup,
        String industry,
        BigDecimal latestPrice,
        Instant marketTimestamp,
        BigDecimal changePercent,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal amount,
        MispricingScoreBreakdown score,
        String action,
        String actionLabel,
        String reason,
        TradingAdvice todayAdvice,
        List<String> strengths,
        List<String> risks,
        List<String> entryRules,
        List<String> exitRules,
        List<MispricingEvidenceItem> evidence,
        EvidenceCompleteness evidenceCompleteness,
        RecommendationEvidenceBundle evidenceBundle,
        MispricingReviewResult review
) {
}
