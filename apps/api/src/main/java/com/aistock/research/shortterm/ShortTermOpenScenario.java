package com.aistock.research.shortterm;

import java.util.List;

public record ShortTermOpenScenario(
        String code,
        String label,
        String condition,
        String action,
        List<String> invalidationRules
) {
}
