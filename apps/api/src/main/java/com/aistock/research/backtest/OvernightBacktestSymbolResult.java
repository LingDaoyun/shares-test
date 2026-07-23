package com.aistock.research.backtest;

import java.util.List;

public record OvernightBacktestSymbolResult(
        String symbol,
        String status,
        int klineCount,
        int sampleCount,
        List<String> dataGaps
) {
}
