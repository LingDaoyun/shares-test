package com.aistock.research.dailysignal;

import com.aistock.research.trading.TradingAdvice;

import java.math.BigDecimal;
import java.util.List;

public record DailyDecisionSignal(
        int rank,
        String symbol,
        String name,
        String market,
        String sourceType,
        String sourceLabel,
        String action,
        String actionLabel,
        int confidence,
        BigDecimal score,
        String horizon,
        String marketPhase,
        TradingAdvice todayAdvice,
        List<String> strategyTags,
        String reason,
        String riskSummary,
        String catalystSummary,
        List<String> watchConditions,
        List<DailySignalEvidence> evidence
) {
}
