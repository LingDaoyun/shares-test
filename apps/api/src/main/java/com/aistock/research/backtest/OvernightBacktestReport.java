package com.aistock.research.backtest;

import java.time.Instant;
import java.util.List;

public record OvernightBacktestReport(
        String scope,
        List<String> validationScope,
        List<String> unreplayedGates,
        List<String> methodology,
        OvernightBacktestRuleSet ruleSet,
        List<String> symbols,
        String status,
        String message,
        OvernightBacktestSummary summary,
        List<OvernightBacktestSymbolResult> results,
        List<OvernightBacktestTrade> trades,
        Instant generatedAt
) {
}
