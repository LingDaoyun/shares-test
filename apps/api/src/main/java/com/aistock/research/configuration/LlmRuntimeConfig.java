package com.aistock.research.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record LlmRuntimeConfig(
        @NotBlank String provider,
        String apiKey,
        String apiKeyEnv,
        @NotBlank String model,
        @NotBlank String baseUrl,
        @NotBlank String responseFormat,
        boolean strictJsonSchema,
        String thinking,
        @Positive Integer maxCompletionTokens,
        Double temperature,
        boolean apiKeyConfigured,
        String apiKeySource
) {
}
