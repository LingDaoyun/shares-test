package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermCoreSignalScore(
        BigDecimal goldenCrossScore,
        BigDecimal volumeScore,
        BigDecimal turnoverScore,
        BigDecimal closeStrengthScore,
        BigDecimal finalScore
) {
}
