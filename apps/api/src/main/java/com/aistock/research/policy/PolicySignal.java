package com.aistock.research.policy;

public record PolicySignal(
        String source,
        String signalType,
        String summary,
        int confidence,
        String url,
        String publishedAt,
        String excerpt,
        boolean planningBaseline,
        PolicySourceInfo sourceInfo
) {
    public PolicySignal(String source, String signalType, String summary, int confidence,
                        String url, String publishedAt) {
        this(source, signalType, summary, confidence, url, publishedAt, null, false, null);
    }
    public PolicySignal(String source, String signalType, String summary, int confidence,
                        String url, String publishedAt, String excerpt, boolean planningBaseline) {
        this(source, signalType, summary, confidence, url, publishedAt, excerpt, planningBaseline, null);
    }
}
