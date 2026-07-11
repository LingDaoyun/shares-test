package com.aistock.research.ai;

import java.util.List;
import java.util.Map;

public record TrendPromptPreview(
        String name,
        String version,
        String modelInstruction,
        String userPrompt,
        Map<String, Object> outputSchema,
        List<String> qualityChecklist,
        List<String> guardrails
) {
}
