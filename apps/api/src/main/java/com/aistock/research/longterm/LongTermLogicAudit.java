package com.aistock.research.longterm;

import java.util.List;

public record LongTermLogicAudit(
        String quarterlyReview,
        String annualReview,
        List<String> eventTriggers,
        List<String> invalidationConditions,
        String reentryRule
) {
}
