package com.aistock.research.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestTrade(
        String symbol,
        LocalDate signalDate,
        LocalDate entryDate,
        LocalDate exitDate,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal grossReturnPercent,
        BigDecimal returnPercent,
        BigDecimal maxDrawdownPercent,
        BigDecimal totalCostPercent,
        int holdingDays,
        String exitReason,
        List<String> signalEvidence
) {
}
