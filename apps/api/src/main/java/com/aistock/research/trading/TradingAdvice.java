package com.aistock.research.trading;

import java.util.List;

public record TradingAdvice(
        String action,
        String actionLabel,
        int confidence,
        String summary,
        List<String> reasons,
        List<String> riskControls
) {
}
