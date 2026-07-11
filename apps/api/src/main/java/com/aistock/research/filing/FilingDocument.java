package com.aistock.research.filing;

import java.util.List;

public record FilingDocument(
        String documentId,
        String title,
        String source,
        String category,
        String publishedAt,
        String sourceUrl,
        String downloadUrl,
        List<String> matchedKeywords,
        int confidence
) {
}
