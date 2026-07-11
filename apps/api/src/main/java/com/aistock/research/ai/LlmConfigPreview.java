package com.aistock.research.ai;

public record LlmConfigPreview(
        String provider,
        String model,
        String baseUrl,
        String responseFormat,
        boolean strictJsonSchema,
        boolean apiKeyConfigured,
        String apiKeySource,
        String thinking,
        Integer maxCompletionTokens,
        Double temperature
) {
}
