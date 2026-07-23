package com.aistock.research.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OvernightBacktestTrade(
        String symbol,
        LocalDate signalDate,
        BigDecimal proxyEntryPrice,
        LocalDate t1Date,
        LocalDate t2Date,
        LocalDate exitDate,
        BigDecimal exitPrice,
        BigDecimal netReturnPercent,
        BigDecimal maxRunupPercent,
        BigDecimal maxDrawdownPercent,
        BigDecimal gapPercent,
        int holdingTradingDays,
        BigDecimal commissionCostPercent,
        BigDecimal stampDutyCostPercent,
        BigDecimal slippageCostPercent,
        BigDecimal totalCostPercent,
        String exitReason
) {
}
