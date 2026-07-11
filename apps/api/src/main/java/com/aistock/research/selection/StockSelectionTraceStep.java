package com.aistock.research.selection;

import java.util.List;

public record StockSelectionTraceStep(
        String stepCode,
        String actor,
        String conclusion,
        List<String> evidenceRefs
) {
}
