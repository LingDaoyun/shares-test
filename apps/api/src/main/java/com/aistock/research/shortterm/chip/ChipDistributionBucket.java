package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;

public record ChipDistributionBucket(
        BigDecimal lowPrice,
        BigDecimal highPrice,
        BigDecimal price,
        BigDecimal chipRatioPercent,
        BigDecimal normalizedHeight
) {
}
