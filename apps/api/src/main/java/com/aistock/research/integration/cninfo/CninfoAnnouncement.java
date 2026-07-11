package com.aistock.research.integration.cninfo;

public record CninfoAnnouncement(
        String announcementId,
        String symbol,
        String name,
        String orgId,
        String title,
        long announcementTime,
        String adjunctUrl
) {
}
