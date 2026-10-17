package com.aistock.research.integration.gov;

public record GovPolicyItem(
        String source,
        String sourceType,
        String title,
        String url,
        String publishedAt,
        int sourceWeight,
        String bodyText,
        boolean planningBaseline
) {
    public GovPolicyItem(String source, String sourceType, String title, String url, String publishedAt, int sourceWeight) {
        this(source, sourceType, title, url, publishedAt, sourceWeight, null, false);
    }
}
