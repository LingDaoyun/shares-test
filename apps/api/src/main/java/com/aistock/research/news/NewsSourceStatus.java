package com.aistock.research.news;

import java.time.Instant;

public record NewsSourceStatus(String id, String name, String category, String url,
                               String status, int itemCount, Instant lastAttemptAt,
                               Instant lastSuccessAt, String message) { }
