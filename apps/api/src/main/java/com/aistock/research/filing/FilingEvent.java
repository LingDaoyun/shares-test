package com.aistock.research.filing;

public record FilingEvent(
        String eventType,
        String eventLabel,
        String severity,
        String documentId,
        String documentTitle,
        String evidenceText,
        String sourceUrl,
        int confidence
) {
}
