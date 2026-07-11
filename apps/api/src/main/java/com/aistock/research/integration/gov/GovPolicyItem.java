package com.aistock.research.integration.gov;

public record GovPolicyItem(
        String source,
        String sourceType,
        String title,
        String url,
        String publishedAt,
        int sourceWeight
) {
}
