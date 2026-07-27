package com.aistock.research.configuration;

import com.aistock.research.ai.LlmConfigPreview;
import com.aistock.research.ai.LlmTrendAnalysisService;
import com.aistock.research.config.LiveDataProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {

    private final Yaml yaml = new Yaml();
    private final AtomicReference<Response> getResponse = new AtomicReference<>();
    private final AtomicReference<String> publishedContent = new AtomicReference<>();
    private final AtomicInteger publishCount = new AtomicInteger();
    private HttpServer server;
    private RuntimeConfigService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/nacos/v1/cs/configs", this::handleConfig);
        server.start();

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:" + server.getAddress().getPort())
                .withProperty("spring.cloud.nacos.config.group", "AI_STOCK")
                .withProperty("NACOS_CONFIG_DATA_ID", "ai-stock-api.yml")
                .withProperty("research.ai.llm.api-key-env", "DEEPSEEK_API_KEY");
        LlmTrendAnalysisService llmService = mock(LlmTrendAnalysisService.class);
        when(llmService.currentConfig()).thenReturn(new LlmConfigPreview(
                "deepseek", "deepseek-v4-pro", "https://api.deepseek.com",
                "json_object", false, true, "research.ai.llm.api-key",
                null, 8192, null));
        LiveDataProperties liveData = new LiveDataProperties(
                null, null, 80, "https://quote.example", "https://financial.example",
                "https://filing.example", "https://gov.example", true,
                12, 2, 6,
                List.of(new LiveDataProperties.PolicySourceProperties(
                        "现有政策源", "json", "https://existing.example", 90)));
        service = new RuntimeConfigService(environment, liveData, llmService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void updatesOnlyLlmAndPreservesPolicySourcesUnknownNodesAndExistingKey() {
        getResponse.set(new Response(200, existingYaml()));

        LlmRuntimeConfig result = service.updateLlmConfig(llmRequest(null));

        Map<String, Object> published = publishedYaml();
        assertThat(path(published, "research", "future-module", "enabled")).isEqualTo(true);
        assertThat(path(published, "research", "live-data", "policy-sources")).isEqualTo(List.of(Map.of(
                "name", "保留政策源", "type", "html", "url", "https://policy.example", "weight", 88)));
        assertThat(path(published, "research", "ai", "llm", "model")).isEqualTo("deepseek-v4-pro");
        assertThat(path(published, "research", "ai", "llm", "api-key")).isEqualTo("existing-secret");
        assertThat(result.apiKey()).isNull();
        assertThat(result.apiKeyConfigured()).isTrue();
    }

    @Test
    void replacesApiKeyWithoutReturningIt() {
        getResponse.set(new Response(200, existingYaml()));

        LlmRuntimeConfig result = service.updateLlmConfig(llmRequest("new-secret"));

        assertThat(path(publishedYaml(), "research", "ai", "llm", "api-key")).isEqualTo("new-secret");
        assertThat(result.apiKey()).isNull();
        assertThat(result.apiKeyConfigured()).isTrue();
        assertThat(result.apiKeySource()).isEqualTo("research.ai.llm.api-key");
    }

    @Test
    void updatesOnlyPolicySourcesAndPreservesLlmAndUnknownNodes() {
        getResponse.set(new Response(200, existingYaml()));
        List<PolicySourceConfig> sources = List.of(
                new PolicySourceConfig("中国政府网", "json", "https://gov.example", 100));

        List<PolicySourceConfig> result = service.updatePolicySources(sources);

        Map<String, Object> published = publishedYaml();
        assertThat(path(published, "research", "ai", "llm", "api-key")).isEqualTo("existing-secret");
        assertThat(path(published, "research", "future-module", "enabled")).isEqualTo(true);
        assertThat(path(published, "research", "live-data", "policy-sources")).isEqualTo(List.of(Map.of(
                "name", "中国政府网", "type", "json", "url", "https://gov.example", "weight", 100)));
        assertThat(result).isEqualTo(sources);
    }

    @Test
    void doesNotPublishWhenNacosReadFails() {
        getResponse.set(new Response(500, "unavailable"));

        assertThatThrownBy(() -> service.updateLlmConfig(llmRequest(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nacos 请求失败 HTTP 500");
        assertThat(publishCount).hasValue(0);
    }

    @Test
    void createsBaseDocumentWhenNacosConfigDoesNotExist() {
        getResponse.set(new Response(404, "config data not exist"));

        service.updatePolicySources(List.of(
                new PolicySourceConfig("新政策源", "html", "https://new.example", 80)));

        Map<String, Object> published = publishedYaml();
        assertThat(path(published, "research", "ai", "llm", "provider")).isEqualTo("deepseek");
        assertThat(path(published, "research", "live-data", "policy-sources")).isEqualTo(List.of(Map.of(
                "name", "新政策源", "type", "html", "url", "https://new.example", "weight", 80)));
    }

    private LlmRuntimeConfig llmRequest(String apiKey) {
        return new LlmRuntimeConfig(
                "deepseek", apiKey, "DEEPSEEK_API_KEY", "deepseek-v4-pro",
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null, false, "missing");
    }

    private String existingYaml() {
        return """
                research:
                  ai:
                    llm:
                      provider: deepseek
                      api-key: existing-secret
                      model: old-model
                  live-data:
                    stock-limit: 80
                    policy-sources:
                      - name: 保留政策源
                        type: html
                        url: https://policy.example
                        weight: 88
                  future-module:
                    enabled: true
                """;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedYaml() {
        assertThat(publishCount).hasValue(1);
        return yaml.load(publishedContent.get());
    }

    @SuppressWarnings("unchecked")
    private Object path(Map<String, Object> root, String... segments) {
        Object value = root;
        for (String segment : segments) {
            value = ((Map<String, Object>) value).get(segment);
        }
        return value;
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            Response response = getResponse.get();
            write(exchange, response.status(), response.body());
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            publishedContent.set(parseForm(form).get("content"));
            publishCount.incrementAndGet();
            write(exchange, 200, "true");
            return;
        }
        write(exchange, 405, "method not allowed");
    }

    private Map<String, String> parseForm(String form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String body) {
    }
}
