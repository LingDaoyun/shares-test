package com.aistock.research.filing;

import java.time.Instant;
import java.util.List;

public record FilingEvidenceSummary(
        String symbol,
        String status,
        String statusLabel,
        int totalDocuments,
        int parsedDocuments,
        List<FilingDocument> documents,
        List<FilingEvent> extractedEvents,
        List<String> moatSignals,
        List<String> riskSignals,
        List<String> validationSignals,
        List<String> dataGaps,
        Instant updatedAt
) {
}
