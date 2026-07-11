package com.aistock.research.research;

import java.math.BigDecimal;
import java.util.List;

public record DimensionScore(
        String code,
        String name,
        BigDecimal score,
        String verdict,
        List<String> evidenceRefs,
        List<String> nextChecks
) {
}
