package com.aistock.research.market.context;

import java.util.List;

public record LongTermPolicyDocument(
        String title,
        String source,
        String publishedAt,
        String url,
        String impact,
        int relevanceScore,
        List<String> matchedKeywords,
        String rationale
) {
}
