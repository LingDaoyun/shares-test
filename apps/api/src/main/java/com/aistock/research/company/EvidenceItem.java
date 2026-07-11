package com.aistock.research.company;

public record EvidenceItem(
        String sourceType,
        String sourceTitle,
        String excerpt,
        String url,
        int confidence
) {
}

