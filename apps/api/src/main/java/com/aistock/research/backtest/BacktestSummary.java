package com.aistock.research.backtest;

import java.math.BigDecimal;

public record BacktestSummary(
        int symbolCount,
        int tradeCount,
        int winCount,
        BigDecimal winRatePercent,
        BigDecimal averageReturnPercent,
        BigDecimal averageMaxDrawdownPercent,
        BigDecimal bestReturnPercent,
        BigDecimal worstReturnPercent,
        BigDecimal profitFactor,
        String conclusion
) {
}
