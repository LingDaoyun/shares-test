package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.valuation.ValuationContext;
import com.aistock.research.shortterm.chip.ShortTermChipSnapshot;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermCandidate(
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
        QuoteFreshnessSnapshot quoteFreshness,
        ValuationContext valuationContext,
        String phase,
        String phaseLabel,
        String action,
        String actionLabel,
        String reason,
        TradingAdvice todayAdvice,
        ShortTermTailSignal tailSignal,
        ShortTermScoreBreakdown score,
        ShortTermTechnicalSnapshot technical,
        ShortTermFinancialSnapshot financial,
        BigDecimal buyZoneLow,
        BigDecimal buyZoneHigh,
        BigDecimal stopPrice,
        List<String> strengths,
        List<String> risks,
        List<String> entryRules,
        List<String> exitRules,
        EvidenceCompleteness evidenceCompleteness,
        List<ShortTermEvidence> evidence,
        ShortTermTradePlan tradePlan,
        ShortTermChipSnapshot chip
) {
    public ShortTermCandidate(
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
            QuoteFreshnessSnapshot quoteFreshness,
            ValuationContext valuationContext,
            String phase,
            String phaseLabel,
            String action,
            String actionLabel,
            String reason,
            TradingAdvice todayAdvice,
            ShortTermTailSignal tailSignal,
            ShortTermScoreBreakdown score,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            BigDecimal buyZoneLow,
            BigDecimal buyZoneHigh,
            BigDecimal stopPrice,
            List<String> strengths,
            List<String> risks,
            List<String> entryRules,
            List<String> exitRules,
            EvidenceCompleteness evidenceCompleteness,
            List<ShortTermEvidence> evidence,
            ShortTermTradePlan tradePlan
    ) {
        this(
                rank, symbol, name, market, industry, latestPrice, changePercent,
                peTtm, pbRatio, amount, quoteFreshness, valuationContext,
                phase, phaseLabel, action, actionLabel, reason, todayAdvice, tailSignal,
                score, technical, financial, buyZoneLow, buyZoneHigh, stopPrice,
                strengths, risks, entryRules, exitRules, evidenceCompleteness, evidence,
                tradePlan, null
        );
    }
}
