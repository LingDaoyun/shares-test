package com.aistock.research.news;

public record FinancialNewsItem(String id, String category, String title, String source,
                                String url, String publishedAt, String summary) { }
