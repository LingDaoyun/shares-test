package com.aistock.research.cycle;

import com.aistock.research.trading.TradingAdvice;

import java.math.BigDecimal;
import java.util.List;

public record CycleTrialCandidate(
        int rank,
        String symbol,
        String name,
        String assetGroup,
        String cycleDriver,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        CyclePeerValuationSnapshot peerValuation,
        BigDecimal amount,
        String phase,
        String phaseLabel,
        String action,
        String actionLabel,
        String reason,
        TradingAdvice todayAdvice,
        CycleTrialScoreBreakdown score,
        CycleTechnicalSnapshot technical,
        BigDecimal trialBuyZoneLow,
        BigDecimal trialBuyZoneHigh,
        BigDecimal stopPrice,
        List<String> catalysts,
        List<String> risks,
        List<String> entryRules,
        List<String> exitRules,
        List<CycleTrialEvidence> evidence
) {
}
