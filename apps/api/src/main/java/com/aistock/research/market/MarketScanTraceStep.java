package com.aistock.research.market;

import java.util.List;

public record MarketScanTraceStep(
        String step,
        String title,
        String summary,
        List<String> findings,
        String sourceName,
        String sourceUrl
) {
}
