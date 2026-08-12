package com.aistock.research.ai;

public record LlmSettings(
        String provider,
        String apiKey,
        String apiKeySource,
        String model,
        String baseUrl,
        String responseFormat,
        boolean strictJsonSchema,
        String thinking,
        Integer maxCompletionTokens,
        Double temperature
) {

    @Override
    public String toString() {
        return "LlmSettings[provider=" + provider
                + ", apiKey=<redacted>"
                + ", apiKeySource=" + apiKeySource
                + ", model=" + model
                + ", baseUrl=" + baseUrl
                + ", responseFormat=" + responseFormat
                + ", strictJsonSchema=" + strictJsonSchema
                + ", thinking=" + thinking
                + ", maxCompletionTokens=" + maxCompletionTokens
                + ", temperature=" + temperature
                + "]";
    }
}
