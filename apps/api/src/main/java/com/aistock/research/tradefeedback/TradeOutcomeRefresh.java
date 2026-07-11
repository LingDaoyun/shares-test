package com.aistock.research.tradefeedback;

import java.util.List;

public record TradeOutcomeRefresh(List<String> warnings) {

    public TradeOutcomeRefresh {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
