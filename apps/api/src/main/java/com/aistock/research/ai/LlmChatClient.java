package com.aistock.research.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmChatClient {

    private static final String DEEPSEEK_PROVIDER = "deepseek";

    private final ObjectMapper objectMapper;
    private final LlmSettingsProvider settingsProvider;

    public LlmChatClient(ObjectMapper objectMapper, LlmSettingsProvider settingsProvider) {
        this.objectMapper = objectMapper;
        this.settingsProvider = settingsProvider;
    }

    public LlmConfigPreview currentConfig() {
        LlmSettings settings = settingsProvider.current();
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
        LlmSettings settings = settingsProvider.current();
        if (!hasText(settings.apiKey())) {
            throw new IllegalStateException("未配置 LLM API Key，无法执行 Agent AI 辩论增强。请在系统配置中设置 API Key 或 apiKeyEnv");
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

    private String maxTokensField(String provider) {
        return DEEPSEEK_PROVIDER.equals(provider) ? "max_tokens" : "max_completion_tokens";
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

}
