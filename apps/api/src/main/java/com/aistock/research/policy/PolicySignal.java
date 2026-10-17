package com.aistock.research.policy;

public record PolicySignal(
        String source,
        String signalType,
        String summary,
        int confidence,
        String url,
        String publishedAt,
        String excerpt,
        boolean planningBaseline
) {
    public PolicySignal(String source, String signalType, String summary, int confidence,
                        String url, String publishedAt) {
        this(source, signalType, summary, confidence, url, publishedAt, null, false);
    }
}
