package com.aistock.research.integration.news;

public record IndustryNewsItem(
        String id,
        String title,
        String source,
        String url,
        String publishedAt,
        String excerpt
) {
}
