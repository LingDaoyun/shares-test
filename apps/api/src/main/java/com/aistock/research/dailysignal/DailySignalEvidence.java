package com.aistock.research.dailysignal;

public record DailySignalEvidence(
        String title,
        String summary,
        String url,
        int weight
) {
}
