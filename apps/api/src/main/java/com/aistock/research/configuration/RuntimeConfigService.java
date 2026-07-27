package com.aistock.research.configuration;

import com.aistock.research.ai.LlmConfigPreview;
import com.aistock.research.ai.LlmTrendAnalysisService;
import com.aistock.research.config.LiveDataProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeConfigService {

    private final Environment environment;
    private final LiveDataProperties liveDataProperties;
    private final LlmTrendAnalysisService llmTrendAnalysisService;
    private final HttpClient httpClient;
    private final Yaml yaml;

    public RuntimeConfigService(
            Environment environment,
            LiveDataProperties liveDataProperties,
            LlmTrendAnalysisService llmTrendAnalysisService
    ) {
        this.environment = environment;
        this.liveDataProperties = liveDataProperties;
        this.llmTrendAnalysisService = llmTrendAnalysisService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.yaml = new Yaml(options);
    }

    public RuntimeConfigSnapshot currentConfig() {
        LlmConfigPreview llm = llmTrendAnalysisService.currentConfig();
        List<PolicySourceConfig> policySources = liveDataProperties.policySources() == null
                ? List.of()
                : liveDataProperties.policySources().stream()
                .map(source -> new PolicySourceConfig(source.name(), source.type(), source.url(), source.weight()))
                .toList();
        return new RuntimeConfigSnapshot(
                dataId(),
                group(),
                new LlmRuntimeConfig(
                        llm.provider(),
                        null,
                        nullToBlank(environment.getProperty("research.ai.llm.api-key-env")),
                        llm.model(),
                        llm.baseUrl(),
                        llm.responseFormat(),
                        llm.strictJsonSchema(),
                        llm.thinking(),
                        llm.maxCompletionTokens(),
                        llm.temperature(),
                        llm.apiKeyConfigured(),
                        llm.apiKeySource()
                ),
                policySources,
                Instant.now()
        );
    }

    public LlmRuntimeConfig currentLlmConfig() {
        return currentConfig().llm();
    }

    public List<PolicySourceConfig> currentPolicySources() {
        return currentConfig().policySources();
    }

    public LlmRuntimeConfig updateLlmConfig(LlmRuntimeConfig request) {
        Map<String, Object> root = readRemoteConfigForUpdate();
        Map<String, Object> llm = map(map(map(root, "research"), "ai"), "llm");
        LlmRuntimeConfig before = currentLlmConfig();
        boolean replacedKey = request.apiKey() != null && !request.apiKey().isBlank();
        applyLlmConfig(llm, request);
        publishRemoteConfig(yaml.dump(root));
        return new LlmRuntimeConfig(
                request.provider().trim(),
                null,
                nullToBlank(request.apiKeyEnv()).trim(),
                request.model().trim(),
                request.baseUrl().trim(),
                request.responseFormat().trim(),
                request.strictJsonSchema(),
                trimmedOrNull(request.thinking()),
                request.maxCompletionTokens(),
                request.temperature(),
                replacedKey || before.apiKeyConfigured(),
                replacedKey ? "research.ai.llm.api-key" : before.apiKeySource()
        );
    }

    public List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request) {
        Map<String, Object> root = readRemoteConfigForUpdate();
        Map<String, Object> liveData = map(map(root, "research"), "live-data");
        List<PolicySourceConfig> sources = List.copyOf(request);
        liveData.put("policy-sources", sources.stream().map(this::policySourceMap).toList());
        publishRemoteConfig(yaml.dump(root));
        return sources;
    }

    public RuntimeConfigSnapshot updateConfig(RuntimeConfigSnapshot request) {
        Map<String, Object> root = readRemoteConfig();
        Map<String, Object> research = map(root, "research");
        Map<String, Object> ai = map(research, "ai");
        Map<String, Object> llm = map(ai, "llm");
        Map<String, Object> liveData = map(research, "live-data");

        if (request.llm() != null) {
            applyLlmConfig(llm, request.llm());
        }
        if (request.policySources() != null) {
            liveData.put("policy-sources", request.policySources().stream()
                    .map(this::policySourceMap)
                    .toList());
        }

        publishRemoteConfig(yaml.dump(root));
        return currentConfig();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRemoteConfig() {
        try {
            String body = send(HttpRequest.newBuilder()
                    .uri(URI.create(nacosBaseUrl() + "/nacos/v1/cs/configs?" + configQuery()))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build());
            Object parsed = yaml.load(body);
            if (parsed instanceof Map<?, ?> parsedMap) {
                return (Map<String, Object>) parsedMap;
            }
        } catch (Exception ignored) {
            // Fall through to a snapshot based on the effective runtime config.
        }
        return fallbackConfigMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRemoteConfigForUpdate() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nacosBaseUrl() + "/nacos/v1/cs/configs?" + configQuery()))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return fallbackConfigMap();
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        "Nacos 请求失败 HTTP " + response.statusCode() + ": " + response.body());
            }
            Object parsed = yaml.load(response.body());
            if (parsed instanceof Map<?, ?> parsedMap) {
                return (Map<String, Object>) parsedMap;
            }
            throw new IllegalStateException("Nacos 配置不是有效的 YAML 对象");
        } catch (IOException exception) {
            throw new IllegalStateException("Nacos 配置读取失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nacos 配置读取被中断", exception);
        }
    }

    private void publishRemoteConfig(String content) {
        String body = form(
                "dataId", dataId(),
                "group", group(),
                "type", "yaml",
                "content", content
        );
        try {
            String result = send(HttpRequest.newBuilder()
                    .uri(URI.create(nacosBaseUrl() + "/nacos/v1/cs/configs"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
            if (!result.trim().contains("true")) {
                throw new IllegalStateException("Nacos 配置发布失败：" + result);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Nacos 配置发布失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nacos 配置发布被中断", exception);
        }
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Nacos 请求失败 HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private void applyLlmConfig(Map<String, Object> llm, LlmRuntimeConfig config) {
        putIfText(llm, "provider", config.provider());
        putSecretIfText(llm, "api-key", config.apiKey());
        putIfText(llm, "api-key-env", config.apiKeyEnv());
        putIfText(llm, "model", config.model());
        putIfText(llm, "base-url", config.baseUrl());
        putIfText(llm, "response-format", config.responseFormat());
        llm.put("strict-json-schema", config.strictJsonSchema());
        putNullableText(llm, "thinking", config.thinking());
        putNullable(llm, "max-completion-tokens", config.maxCompletionTokens());
        putNullable(llm, "temperature", config.temperature());
    }

    private Map<String, Object> policySourceMap(PolicySourceConfig source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", source.name());
        map.put("type", source.type());
        map.put("url", source.url());
        map.put("weight", source.weight());
        return map;
    }

    private Map<String, Object> fallbackConfigMap() {
        RuntimeConfigSnapshot snapshot = currentConfig();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", snapshot.llm().provider());
        llm.put("api-key", "${" + defaultKeyEnv(snapshot.llm().provider()) + ":}");
        llm.put("api-key-env", defaultKeyEnv(snapshot.llm().provider()));
        llm.put("model", snapshot.llm().model());
        llm.put("base-url", snapshot.llm().baseUrl());
        llm.put("response-format", snapshot.llm().responseFormat());
        llm.put("strict-json-schema", snapshot.llm().strictJsonSchema());
        if (snapshot.llm().maxCompletionTokens() != null) {
            llm.put("max-completion-tokens", snapshot.llm().maxCompletionTokens());
        }

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("llm", llm);
        Map<String, Object> liveData = new LinkedHashMap<>();
        liveData.put("stock-limit", liveDataProperties.stockLimit());
        liveData.put("eastmoney-quote-url", liveDataProperties.eastmoneyQuoteUrl());
        liveData.put("eastmoney-financial-url", liveDataProperties.eastmoneyFinancialUrl());
        liveData.put("cninfo-announcement-url", liveDataProperties.cninfoAnnouncementUrl());
        liveData.put("gov-policy-url", liveDataProperties.govPolicyUrl());
        liveData.put("fast-company-list", liveDataProperties.fastCompanyList());
        liveData.put("filing-limit", liveDataProperties.filingLimit());
        liveData.put("filing-pdf-parse-limit", liveDataProperties.filingPdfParseLimit());
        liveData.put("filing-pdf-max-pages", liveDataProperties.filingPdfMaxPages());
        liveData.put("policy-sources", snapshot.policySources().stream().map(this::policySourceMap).toList());
        Map<String, Object> research = new LinkedHashMap<>();
        research.put("ai", ai);
        research.put("live-data", liveData);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("research", research);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private void putSecretIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private void putNullableText(Map<String, Object> map, String key, String value) {
        if (value == null || value.isBlank()) {
            map.remove(key);
        } else {
            map.put(key, value.trim());
        }
    }

    private void putNullable(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    private String configQuery() {
        List<String> params = new ArrayList<>();
        params.add("dataId=" + encode(dataId()));
        params.add("group=" + encode(group()));
        String namespace = namespace();
        if (namespace != null && !namespace.isBlank()) {
            params.add("tenant=" + encode(namespace));
        }
        return String.join("&", params);
    }

    private String form(String... values) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            pairs.add(encode(values[i]) + "=" + encode(values[i + 1]));
        }
        String namespace = namespace();
        if (namespace != null && !namespace.isBlank()) {
            pairs.add("tenant=" + encode(namespace));
        }
        return String.join("&", pairs);
    }

    private String nacosBaseUrl() {
        String server = firstNonBlank(
                environment.getProperty("spring.cloud.nacos.config.server-addr"),
                environment.getProperty("NACOS_SERVER_ADDR"),
                "127.0.0.1:8848"
        );
        if (server.startsWith("http://") || server.startsWith("https://")) {
            return server;
        }
        return "http://" + server;
    }

    private String dataId() {
        return firstNonBlank(environment.getProperty("NACOS_CONFIG_DATA_ID"), "ai-stock-api.yml");
    }

    private String group() {
        return firstNonBlank(
                environment.getProperty("spring.cloud.nacos.config.group"),
                environment.getProperty("NACOS_GROUP"),
                "AI_STOCK"
        );
    }

    private String namespace() {
        return firstNonBlank(
                environment.getProperty("spring.cloud.nacos.config.namespace"),
                environment.getProperty("NACOS_NAMESPACE")
        );
    }

    private String defaultKeyEnv(String provider) {
        if ("deepseek".equals(provider)) {
            return "DEEPSEEK_API_KEY";
        }
        if ("moonshot".equals(provider) || "kimi".equals(provider)) {
            return "MOONSHOT_API_KEY";
        }
        if ("kimi-code".equals(provider)) {
            return "KIMI_API_KEY";
        }
        return "OPENAI_API_KEY";
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
