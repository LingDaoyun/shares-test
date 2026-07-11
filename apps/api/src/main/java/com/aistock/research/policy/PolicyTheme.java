package com.aistock.research.policy;

import java.math.BigDecimal;
import java.util.List;

public record PolicyTheme(
        String themeCode,
        String name,
        String policyLevel,
        String timeHorizon,
        BigDecimal strengthScore,
        List<String> chainSegments,
        List<PolicySignal> signals,
        List<String> risks
) {
}

