package com.aistock.research.backtest;

import java.time.Instant;
import java.util.List;

public record OvernightBacktestReport(
        String scope,
        List<String> methodology,
        OvernightBacktestRuleSet ruleSet,
        List<String> symbols,
        OvernightBacktestSummary summary,
        List<OvernightBacktestTrade> trades,
        Instant generatedAt
) {
}
