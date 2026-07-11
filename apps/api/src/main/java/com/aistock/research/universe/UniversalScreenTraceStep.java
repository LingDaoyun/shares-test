package com.aistock.research.universe;

import java.util.List;

public record UniversalScreenTraceStep(
        String step,
        String title,
        String summary,
        List<String> findings,
        String sourceName,
        String sourceUrl
) {
}
