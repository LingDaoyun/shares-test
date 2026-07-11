package com.aistock.research.ai;

import com.aistock.research.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LlmChatClient {

    private static final String OPENAI_PROVIDER = "openai";
    private static final String MOONSHOT_PROVIDER = "moonshot";
    private static final String KIMI_CODE_PROVIDER = "kimi-code";
    private static final String DEEPSEEK_PROVIDER = "deepseek";

    private final ObjectMapper objectMapper;
    private final LlmProperties properties;
    private final Environment environment;

    public LlmChatClient(ObjectMapper objectMapper, LlmProperties properties, Environment environment) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.environment = environment;
    }

    public LlmConfigPreview currentConfig() {
        LlmSettings settings = settings();
        return new LlmConfigPreview(
                settings.provider(),
                settings.model(),
                settings.baseUrl(),
                settings.responseFormat(),
                settings.strictJsonSchema(),
                hasText(settings.apiKey()),
                settings.apiKeySource(),
                settings.thinking(),
                settings.maxCompletionTokens(),
                settings.temperature()
        );
    }

    public JsonNode completeJson(String systemPrompt, String userPrompt, String schemaName, Map<String, Object> schema) {
        LlmSettings settings = settings();
        if (!hasText(settings.apiKey())) {
            throw new IllegalStateException("未配置 LLM API Key，无法执行 Agent AI 辩论增强。请配置 research.ai.llm.api-key 或 " + defaultKeyEnv(settings.provider()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        responseFormat(settings, schemaName, schema).ifPresent(format -> body.put("response_format", format));
        if (hasText(settings.thinking())) {
            body.put("thinking", Map.of("type", settings.thinking()));
        }
        if (settings.maxCompletionTokens() != null && settings.maxCompletionTokens() > 0) {
            body.put(maxTokensField(settings.provider()), settings.maxCompletionTokens());
        }
        if (settings.temperature() != null) {
            body.put("temperature", settings.temperature());
        }

        String responseBody = callChatCompletions(settings, body);
        JsonNode response = parseJson(responseBody, settings.provider(), "LLM 响应体不是有效 JSON");
        String content = extractMessageContent(response);
        if (!hasText(content)) {
            throw new IllegalStateException("LLM 返回内容为空(provider=" + settings.provider() + "): " + summarize(response.toString()));
        }
        return parseContentJson(content, settings.provider());
    }

    private String callChatCompletions(LlmSettings settings, Map<String, Object> body) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("LLM 请求体序列化失败(provider=" + settings.provider() + ")", exception);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(settings.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(150))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = null;
        IOException lastIOException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                response = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LLM 调用被中断(provider=" + settings.provider() + ")", exception);
            } catch (IOException exception) {
                lastIOException = exception;
                if (attempt == 2) {
                    throw new IllegalStateException("LLM 调用失败(provider=" + settings.provider() + "): " + exception.getMessage(), exception);
                }
            }
        }
        if (response == null && lastIOException != null) {
            throw new IllegalStateException("LLM 调用失败(provider=" + settings.provider() + "): " + lastIOException.getMessage(), lastIOException);
        }
        if (response == null) {
            throw new IllegalStateException("LLM 调用失败(provider=" + settings.provider() + ")");
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("LLM 调用失败(provider=" + settings.provider() + "): HTTP "
                    + response.statusCode() + " " + summarize(response.body()));
        }
        return response.body();
    }

    private java.util.Optional<Map<String, Object>> responseFormat(
            LlmSettings settings,
            String schemaName,
            Map<String, Object> schema
    ) {
        String mode = settings.responseFormat();
        if (!hasText(mode) || "none".equals(mode) || "text".equals(mode)) {
            return java.util.Optional.empty();
        }
        if ("json_object".equals(mode)) {
            return java.util.Optional.of(Map.of("type", "json_object"));
        }
        if ("json_schema".equals(mode)) {
            return java.util.Optional.of(Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", schemaName.replace("-", "_"),
                            "schema", schema,
                            "strict", settings.strictJsonSchema()
                    )
            ));
        }
        throw new IllegalArgumentException("不支持的 LLM response-format: " + mode);
    }

    private JsonNode parseContentJson(String content, String provider) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception strictException) {
            JsonNode recovered = recoverJsonObject(content);
            if (recovered != null) {
                return recovered;
            }
            throw new IllegalStateException("LLM 返回内容不是有效 JSON(provider=" + provider + ")", strictException);
        }
    }

    private JsonNode recoverJsonObject(String content) {
        int searchFrom = 0;
        while (searchFrom < content.length()) {
            int start = content.indexOf('{', searchFrom);
            if (start < 0) {
                return null;
            }
            int end = findBalancedJsonObjectEnd(content, start);
            if (end < 0) {
                return null;
            }
            String candidate = content.substring(start, end + 1);
            try {
                return objectMapper.readTree(candidate);
            } catch (Exception ignored) {
                searchFrom = start + 1;
            }
        }
        return null;
    }

    private int findBalancedJsonObjectEnd(String content, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString && current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String extractMessageContent(JsonNode response) {
        JsonNode contentNode = response.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return blankToNull(contentNode.asText());
        }
        if (contentNode.isObject()) {
            return blankToNull(contentNode.path("text").asText(null));
        }
        if (!contentNode.isArray()) {
            return blankToNull(contentNode.asText(null));
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode item : contentNode) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                builder.append(item.asText());
                continue;
            }
            String text = item.path("text").asText(null);
            if (hasText(text)) {
                builder.append(text);
            }
        }
        return blankToNull(builder.toString());
    }

    private JsonNode parseJson(String responseBody, String provider, String message) {
        if (!hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception exception) {
            throw new IllegalStateException(message + "(provider=" + provider + "): " + summarize(responseBody), exception);
        }
    }

    private LlmSettings settings() {
        String provider = canonicalProvider(firstNonBlank(
                property("research.ai.llm.provider", properties.provider()),
                OPENAI_PROVIDER
        ));
        String apiKey = firstNonBlank(
                property("research.ai.llm.api-key", properties.apiKey()),
                legacyOpenAiProperty(provider, "api-key"),
                envValue(property("research.ai.llm.api-key-env", properties.apiKeyEnv())),
                envValue(defaultKeyEnv(provider)),
                legacyOpenAiEnv(provider)
        );
        String model = firstNonBlank(
                property("research.ai.llm.model", properties.model()),
                legacyOpenAiProperty(provider, "model"),
                defaultModel(provider)
        );
        String baseUrl = firstNonBlank(
                property("research.ai.llm.base-url", properties.baseUrl()),
                legacyOpenAiProperty(provider, "base-url"),
                defaultBaseUrl(provider)
        );
        String responseFormat = normalizeResponseFormat(firstNonBlank(
                property("research.ai.llm.response-format", properties.responseFormat()),
                defaultResponseFormat(provider)
        ));
        boolean strictJsonSchema = booleanProperty("research.ai.llm.strict-json-schema", properties.strictJsonSchema(), true);
        String thinking = blankToNull(property("research.ai.llm.thinking", properties.thinking()));
        Integer maxCompletionTokens = firstNonNull(
                integerProperty("research.ai.llm.max-completion-tokens", properties.maxCompletionTokens()),
                defaultMaxCompletionTokens(provider)
        );
        Double temperature = doubleProperty("research.ai.llm.temperature", properties.temperature());

        return new LlmSettings(
                provider,
                apiKey,
                apiKeySource(provider),
                model,
                baseUrl,
                responseFormat,
                strictJsonSchema,
                thinking,
                maxCompletionTokens,
                temperature
        );
    }

    private String canonicalProvider(String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT).trim();
        if ("kimi-code".equals(normalized) || "kimi_code".equals(normalized)) {
            return KIMI_CODE_PROVIDER;
        }
        if ("deepseek".equals(normalized) || "deep_seek".equals(normalized) || "deep-seek".equals(normalized)) {
            return DEEPSEEK_PROVIDER;
        }
        if ("kimi".equals(normalized)) {
            return MOONSHOT_PROVIDER;
        }
        return normalized;
    }

    private String defaultBaseUrl(String provider) {
        if (KIMI_CODE_PROVIDER.equals(provider)) {
            return "https://api.kimi.com/coding/v1";
        }
        if (MOONSHOT_PROVIDER.equals(provider)) {
            return "https://api.moonshot.ai/v1";
        }
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return "https://api.deepseek.com";
        }
        return "https://api.openai.com/v1";
    }

    private String defaultModel(String provider) {
        if (KIMI_CODE_PROVIDER.equals(provider)) {
            return "kimi-for-coding";
        }
        if (MOONSHOT_PROVIDER.equals(provider)) {
            return "kimi-k2.6";
        }
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return "deepseek-v4-pro";
        }
        return "gpt-5.5";
    }

    private String defaultKeyEnv(String provider) {
        if (KIMI_CODE_PROVIDER.equals(provider)) {
            return "KIMI_API_KEY";
        }
        if (MOONSHOT_PROVIDER.equals(provider)) {
            return "MOONSHOT_API_KEY";
        }
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return "DEEPSEEK_API_KEY";
        }
        return "OPENAI_API_KEY";
    }

    private String defaultResponseFormat(String provider) {
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return "json_object";
        }
        return "json_schema";
    }

    private String maxTokensField(String provider) {
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return "max_tokens";
        }
        return "max_completion_tokens";
    }

    private Integer defaultMaxCompletionTokens(String provider) {
        if (DEEPSEEK_PROVIDER.equals(provider)) {
            return 8192;
        }
        return null;
    }

    private String legacyOpenAiProperty(String provider, String field) {
        if (!OPENAI_PROVIDER.equals(provider)) {
            return null;
        }
        return property("research.ai.openai." + field, null);
    }

    private String legacyOpenAiEnv(String provider) {
        if (!OPENAI_PROVIDER.equals(provider)) {
            return null;
        }
        return envValue("OPENAI_API_KEY");
    }

    private String apiKeySource(String provider) {
        if (hasText(property("research.ai.llm.api-key", properties.apiKey()))) {
            return "research.ai.llm.api-key";
        }
        if (OPENAI_PROVIDER.equals(provider) && hasText(property("research.ai.openai.api-key", null))) {
            return "research.ai.openai.api-key";
        }
        String configuredEnv = property("research.ai.llm.api-key-env", properties.apiKeyEnv());
        if (hasText(configuredEnv) && hasText(envValue(configuredEnv))) {
            return "env:" + configuredEnv;
        }
        String defaultEnv = defaultKeyEnv(provider);
        if (hasText(envValue(defaultEnv))) {
            return "env:" + defaultEnv;
        }
        if (OPENAI_PROVIDER.equals(provider) && hasText(envValue("OPENAI_API_KEY"))) {
            return "env:OPENAI_API_KEY";
        }
        return "missing";
    }

    private String normalizeResponseFormat(String responseFormat) {
        return responseFormat.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String property(String name, String fallback) {
        String value = environment.getProperty(name);
        if (!hasText(value)) {
            return fallback;
        }
        return value;
    }

    private boolean booleanProperty(String name, Boolean fallback, boolean defaultValue) {
        String value = environment.getProperty(name);
        if (!hasText(value)) {
            return fallback == null ? defaultValue : fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private Integer integerProperty(String name, Integer fallback) {
        String value = environment.getProperty(name);
        if (!hasText(value)) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private Double doubleProperty(String name, Double fallback) {
        String value = environment.getProperty(name);
        if (!hasText(value)) {
            return fallback;
        }
        return Double.parseDouble(value);
    }

    private String envValue(String envName) {
        if (!hasText(envName)) {
            return null;
        }
        return System.getenv(envName);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private String blankToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String summarize(String body) {
        if (!hasText(body)) {
            return "empty response body";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }

    private record LlmSettings(
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
    }
}
