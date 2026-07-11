package com.aistock.research.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public record TrendAnalysisResponse(
        Long recordId,
        boolean cached,
        String provider,
        String model,
        String promptName,
        String promptVersion,
        String responseId,
        JsonNode analysis,
        Map<String, Object> usage,
        Instant analyzedAt
) {
}
