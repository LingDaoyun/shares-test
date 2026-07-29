package com.aistock.research.longterm;

import java.math.BigDecimal;
import java.util.List;

public record LongTermPositionDiscipline(
        BigDecimal maxSinglePositionPercent,
        BigDecimal maxTopFivePositionPercent,
        int trancheCount,
        BigDecimal declineReviewTriggerPercent,
        List<String> entryConditions,
        List<String> addConditions,
        List<String> reviewTriggers
) {
}
