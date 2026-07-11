package com.aistock.research.mispricing;

public record MispricingEvidenceItem(
        String title,
        String summary,
        String url,
        int weight
) {
}
