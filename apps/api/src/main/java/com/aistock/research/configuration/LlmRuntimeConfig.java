package com.aistock.research.configuration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record LlmRuntimeConfig(
        @NotBlank String provider,
        String apiKey,
        String apiKeyEnv,
        @NotBlank String model,
        @NotBlank
        @Pattern(regexp = "(?i)https?://.+", message = "必须是 http 或 https 地址")
        String baseUrl,
        @NotBlank String responseFormat,
        boolean strictJsonSchema,
        String thinking,
        @Positive Integer maxCompletionTokens,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        boolean apiKeyConfigured,
        String apiKeySource
) {
}
