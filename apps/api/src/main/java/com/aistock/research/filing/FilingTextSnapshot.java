package com.aistock.research.filing;

public record FilingTextSnapshot(
        String documentId,
        String documentTitle,
        int parsedPages,
        String text
) {
}
