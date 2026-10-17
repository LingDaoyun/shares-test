package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;

public record ChipOverheadPressure(
        BigDecimal referencePrice,
        BigDecimal nearChipRatioPercent,
        BigDecimal fartherChipRatioPercent,
        BigDecimal weightedChipRatioPercent
) {
}
