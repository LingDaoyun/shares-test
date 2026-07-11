package com.aistock.research.quality;

import java.util.List;

public record EvidenceCompleteness(
        int score,
        String status,
        String statusLabel,
        boolean allowsBuy,
        List<String> presentEvidence,
        List<String> missingEvidence,
        List<String> riskControls
) {
}
