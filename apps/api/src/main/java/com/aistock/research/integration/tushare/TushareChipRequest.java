package com.aistock.research.integration.tushare;

public record TushareChipRequest(
        String url,
        String jsonBody,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
