package com.aistock.research.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "research.ai.llm")
public record LlmProperties(
        String provider,
        String apiKey,
        String apiKeyEnv,
        String model,
        String baseUrl,
        String responseFormat,
        Boolean strictJsonSchema,
        String thinking,
        Integer maxCompletionTokens,
        Double temperature
) {
}
