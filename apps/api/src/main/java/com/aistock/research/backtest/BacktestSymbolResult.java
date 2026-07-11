package com.aistock.research.backtest;

import java.util.List;

public record BacktestSymbolResult(
        String symbol,
        int klineCount,
        int tradeCount,
        BacktestSummary summary,
        List<BacktestTrade> trades,
        List<String> dataGaps
) {
}
