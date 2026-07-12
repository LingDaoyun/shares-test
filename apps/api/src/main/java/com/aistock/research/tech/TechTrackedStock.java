package com.aistock.research.tech;

import com.aistock.research.trading.TradingAdvice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TechTrackedStock(
        int rank,
        String symbol,
        String name,
        String themeCode,
        String themeName,
        String industry,
        BigDecimal latestPrice,
        Instant marketTimestamp,
        BigDecimal changePercent,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal amount,
        TechScoreBreakdown score,
        String action,
        String actionLabel,
        String reason,
        TradingAdvice todayAdvice,
        List<String> strengths,
        List<String> risks,
        List<String> entryRules,
        List<String> exitRules,
        List<TechEvidenceItem> evidence
) {
}
