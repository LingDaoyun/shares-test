package com.aistock.research.dailysignal;

import java.util.List;

public record StrategyPlaybook(
        String name,
        String displayName,
        String category,
        String description,
        List<Integer> coreRules,
        List<String> requiredTools,
        List<String> triggerRules,
        List<String> exitRules,
        String scoringImpact
) {
}
