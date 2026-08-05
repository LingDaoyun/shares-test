package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;

public record ChipConcentrationZone(
        int rank,
        BigDecimal lowPrice,
        BigDecimal highPrice,
        BigDecimal peakPrice,
        BigDecimal chipRatioPercent,
        BigDecimal distanceToCurrentPricePercent,
        ChipPricePosition positionToCurrentPrice
) {
}
