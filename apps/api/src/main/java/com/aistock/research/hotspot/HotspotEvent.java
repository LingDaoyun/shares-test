package com.aistock.research.hotspot;

import com.aistock.research.integration.news.IndustryNewsItem;

import java.time.Instant;
import java.util.List;

public record HotspotEvent(
        String id,
        String title,
        String eventType,
        List<String> industries,
        List<String> products,
        String summary,
        String catalyst,
        int heatScore,
        String confidence,
        List<IndustryNewsItem> sources,
        List<HotspotRecommendation> recommendations,
        List<String> risks,
        Instant firstSeenAt,
        Instant updatedAt
) { }
