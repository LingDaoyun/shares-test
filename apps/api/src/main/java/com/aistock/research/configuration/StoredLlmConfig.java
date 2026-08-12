package com.aistock.research.configuration;

public record StoredLlmConfig(
        String provider,
        String apiKey,
        String apiKeyEnv,
        String model,
        String baseUrl,
        String responseFormat,
        boolean strictJsonSchema,
        String thinking,
        Integer maxCompletionTokens,
        Double temperature
) {
}
