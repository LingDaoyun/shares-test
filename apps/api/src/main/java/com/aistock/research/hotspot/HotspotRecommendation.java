package com.aistock.research.hotspot;

import com.aistock.research.company.EvidenceItem;
import com.aistock.research.integration.news.IndustryNewsItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record HotspotRecommendation(
        String symbol,
        String name,
        String industry,
        String recommendationReason,
        String benefitMechanism,
        String relationType,
        List<IndustryNewsItem> evidence,
        List<EvidenceItem> companyEvidence,
        List<String> risks,
        String confidence,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        String quoteUrl,
        String quoteAsOf,
        Instant firstSeenAt,
        BigDecimal firstSeenPrice,
        String firstSeenQuoteAsOf
) { }
