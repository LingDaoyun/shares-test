package com.aistock.research.backtest;

import java.time.Instant;
import java.util.List;

public record BacktestReport(
        String scope,
        List<String> methodology,
        BacktestRuleSet ruleSet,
        List<String> symbols,
        BacktestSummary summary,
        List<BacktestSymbolResult> results,
        Instant generatedAt
) {
}
