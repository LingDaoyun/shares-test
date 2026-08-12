package com.aistock.research.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmTrendAnalysisService {

    private static final String DEEPSEEK_PROVIDER = "deepseek";

    private final TrendPromptService trendPromptService;
    private final ObjectMapper objectMapper;
    private final LlmSettingsProvider settingsProvider;

    public LlmTrendAnalysisService(
            TrendPromptService trendPromptService,
            ObjectMapper objectMapper,
            LlmSettingsProvider settingsProvider
    ) {
        this.trendPromptService = trendPromptService;
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
                settings.apiKey() != null && !settings.apiKey().isBlank(),
                settings.apiKeySource(),
                settings.thinking(),
                settings.maxCompletionTokens(),
                settings.temperature()
        );
    }

    public TrendAnalysisResponse analyze(TrendPromptRequest request) {
        LlmSettings settings = settingsProvider.current();
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 LLM API Key，无法执行 AI 趋势分析。请在系统配置中设置 API Key 或 apiKeyEnv");
        }

        TrendPromptPreview prompt = trendPromptService.preview(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.modelInstruction()),
                Map.of("role", "user", "content", userPromptContent(prompt, settings))
        ));
        responseFormat(settings, prompt).ifPresent(format -> body.put("response_format", format));
        if (settings.thinking() != null && !settings.thinking().isBlank()) {
            body.put("thinking", Map.of("type", settings.thinking()));
        }
        if (settings.maxCompletionTokens() != null && settings.maxCompletionTokens() > 0) {
            body.put(maxTokensField(settings.provider()), settings.maxCompletionTokens());
        }
        if (settings.temperature() != null) {
            body.put("temperature", settings.temperature());
        }

        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String responseBody = callChatCompletions(settings, body);
            JsonNode response = parseProviderResponse(responseBody, settings.provider());
            if (response == null) {
                lastFailure = new IllegalStateException("LLM 返回空响应(provider=" + settings.provider() + ")");
            } else {
                try {
                    JsonNode analysis = extractAnalysis(response, settings, prompt);
                    return new TrendAnalysisResponse(
                            null,
                            false,
                            settings.provider(),
                            settings.model(),
                            prompt.name(),
                            prompt.version(),
                            response.path("id").asText(null),
                            analysis,
                            usage(response.path("usage")),
                            Instant.now()
                    );
                } catch (IllegalStateException exception) {
                    lastFailure = exception;
                }
            }
            if (attempt == 2 && lastFailure != null) {
                throw lastFailure;
            }
        }
        throw new IllegalStateException("LLM 分析失败(provider=" + settings.provider() + ")");
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

        if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                    "LLM 调用失败(provider=" + settings.provider() + "): HTTP "
                            + response.statusCode() + " " + summarize(response.body())
            );
        }
        return response.body();
    }

    private String repairAnalysisContent(LlmSettings settings, TrendPromptPreview prompt, String invalidContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "你是一个 JSON 修复器。你只能输出一个合法、可被 JSON.parse 解析的 JSON 对象，不要输出任何解释。"
                ),
                Map.of(
                        "role", "user",
                        "content", repairPromptContent(prompt, invalidContent)
                )
        ));
        responseFormat(settings, prompt).ifPresent(format -> body.put("response_format", format));
        if (settings.maxCompletionTokens() != null && settings.maxCompletionTokens() > 0) {
            body.put(maxTokensField(settings.provider()), Math.min(settings.maxCompletionTokens(), 4096));
        }
        String responseBody = callChatCompletions(settings, body);
        JsonNode response = parseProviderResponse(responseBody, settings.provider());
        String content = response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM JSON 修复结果为空(provider=" + settings.provider() + ")");
        }
        return content;
    }

    private JsonNode extractAnalysis(JsonNode response, LlmSettings settings, TrendPromptPreview prompt) {
        String content = extractMessageContent(response);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(
                    "LLM 返回内容为空(provider=" + settings.provider() + "): "
                            + summarize(response.toString())
            );
        }
        try {
            return parseAnalysisContent(content, settings.provider());
        } catch (IllegalStateException parseException) {
            String repairedContent = repairAnalysisContent(settings, prompt, content);
            return parseAnalysisContent(repairedContent, settings.provider());
        }
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
            if (text != null && !text.isBlank()) {
                builder.append(text);
            }
        }
        return blankToNull(builder.toString());
    }

    private JsonNode parseProviderResponse(String responseBody, String provider) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "LLM 响应体不是有效 JSON(provider=" + provider + "): " + summarize(responseBody),
                    exception
            );
        }
    }

    JsonNode parseAnalysisContent(String content, String provider) {
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

    private String userPromptContent(TrendPromptPreview prompt, LlmSettings settings) {
        if (!"json_object".equals(settings.responseFormat())) {
            return prompt.userPrompt();
        }
        try {
            return """
                    %s

                    输出格式要求：
                    - 最终答案必须是一个可被 JSON.parse 解析的 json 对象。
                    - 不要输出 Markdown、代码块、解释文字、前后缀说明。
                    - 请严格使用下面 JSON 示例里的字段名和层级，字段缺证据时也要保留并写入 evidence_gaps。

                    JSON 示例结构：
                    %s

                    质量检查清单：
                    %s

                    安全约束：
                    %s
                    """.formatted(
                    prompt.userPrompt(),
                    objectMapper.writeValueAsString(prompt.outputSchema()),
                    String.join("；", prompt.qualityChecklist()),
                    String.join("；", prompt.guardrails())
            );
        } catch (Exception exception) {
            return prompt.userPrompt();
        }
    }

    private String repairPromptContent(TrendPromptPreview prompt, String invalidContent) {
        try {
            return """
                    请把下面这段模型输出修复成严格合法的 JSON 对象。

                    修复规则：
                    - 只能输出 JSON 对象本身，不要输出 Markdown、解释、代码块。
                    - 保持原有字段名和层级。
                    - 缺失字段时，按 schema 补空数组、空字符串，或在 evidence_gaps 中说明。
                    - 删除多余前后缀、注释、重复文本和非 JSON 内容。

                    目标 JSON 结构：
                    %s

                    待修复内容：
                    %s
                    """.formatted(
                    objectMapper.writeValueAsString(prompt.outputSchema()),
                    invalidContent
            );
        } catch (Exception exception) {
            return invalidContent;
        }
    }

    private java.util.Optional<Map<String, Object>> responseFormat(LlmSettings settings, TrendPromptPreview prompt) {
        String mode = settings.responseFormat();
        if (mode == null || mode.isBlank() || "none".equals(mode) || "text".equals(mode)) {
            return java.util.Optional.empty();
        }
        if ("json_object".equals(mode)) {
            return java.util.Optional.of(Map.of("type", "json_object"));
        }
        if ("json_schema".equals(mode)) {
            return java.util.Optional.of(Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", prompt.name().replace("-", "_"),
                            "schema", trendPromptService.structuredOutputSchema(),
                            "strict", settings.strictJsonSchema()
                    )
            ));
        }
        throw new IllegalArgumentException("不支持的 LLM response-format: " + mode);
    }

    private String maxTokensField(String provider) {
        return DEEPSEEK_PROVIDER.equals(provider) ? "max_tokens" : "max_completion_tokens";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }

    private Map<String, Object> usage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(usageNode, new TypeReference<>() {
        });
    }

}
