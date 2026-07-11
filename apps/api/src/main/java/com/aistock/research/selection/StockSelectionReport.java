package com.aistock.research.selection;

import java.time.Instant;
import java.util.List;

public record StockSelectionReport(
        String scope,
        int universeCount,
        int reviewedCount,
        int selectedCount,
        List<String> selectionRules,
        List<StockSelectionCandidate> candidates,
        Instant generatedAt
) {
}
