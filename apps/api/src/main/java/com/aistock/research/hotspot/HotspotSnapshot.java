package com.aistock.research.hotspot;

import com.aistock.research.integration.news.IndustryNewsItem;

import java.time.Instant;
import java.util.List;

public record HotspotSnapshot(
        String status,
        Instant generatedAt,
        Instant startedAt,
        Instant lastAttemptAt,
        Instant nextAutomaticRefreshAt,
        Instant refreshAvailableAt,
        String summary,
        int newsCount,
        int companyCount,
        List<IndustryNewsItem> news,
        List<HotspotEvent> events,
        List<String> dataGaps
) {
    static HotspotSnapshot idle() {
        return new HotspotSnapshot("IDLE", null, null, null, null, null,
                "等待首次采集产业与产品新闻", 0, 0, List.of(), List.of(), List.of());
    }
}
