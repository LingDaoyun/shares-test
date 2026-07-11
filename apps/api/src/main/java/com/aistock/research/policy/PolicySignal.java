package com.aistock.research.policy;

public record PolicySignal(
        String source,
        String signalType,
        String summary,
        int confidence,
        String url,
        String publishedAt
) {
}
