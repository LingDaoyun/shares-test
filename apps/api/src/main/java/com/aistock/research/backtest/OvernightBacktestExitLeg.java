package com.aistock.research.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OvernightBacktestExitLeg(
        LocalDate exitDate,
        BigDecimal positionRatio,
        BigDecimal executablePrice,
        String reason
) {
}
