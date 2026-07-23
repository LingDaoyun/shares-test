package com.aistock.research.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OvernightBacktestSummary(
        int symbolCount,
        int sampleCount,
        BigDecimal positiveRatePercent,
        BigDecimal averageReturnPercent,
        BigDecimal medianReturnPercent,
        BigDecimal averageRunupPercent,
        BigDecimal averageDrawdownPercent,
        BigDecimal firstTargetRatePercent,
        BigDecimal secondTargetRatePercent,
        BigDecimal hardStopRatePercent,
        BigDecimal timeStopRatePercent,
        BigDecimal gapDownRatePercent,
        LocalDate sampleStart,
        LocalDate sampleEnd,
        String conclusion
) {
}
