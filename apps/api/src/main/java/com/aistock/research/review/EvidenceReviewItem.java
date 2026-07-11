package com.aistock.research.review;

public record EvidenceReviewItem(
        String agentCode,
        String agentName,
        String requirement,
        String originalStatus,
        String originalStatusLabel,
        String reviewStatus,
        String reviewStatusLabel,
        String searchScope,
        String source,
        String evidenceText,
        String url,
        int confidence,
        String verdict,
        String nextAction
) {
}
