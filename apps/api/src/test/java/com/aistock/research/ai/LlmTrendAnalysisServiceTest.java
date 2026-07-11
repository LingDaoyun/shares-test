package com.aistock.research.ai;

import com.aistock.research.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmTrendAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksWhenApiKeyIsMissing() {
        LlmTrendAnalysisService service = new LlmTrendAnalysisService(
                new TrendPromptService(),
                RestClient.create(),
                objectMapper,
                new LlmProperties("openai", "", null, "gpt-5.5", "https://api.openai.com/v1", "json_schema", true, null, null, null),
                new MockEnvironment()
        );

        assertThatThrownBy(() -> service.analyze(new TrendPromptRequest(
                "测试规划",
                "政府规划文件",
                "测试机构",
                "2026-06-14",
                "https://example.com/policy",
                "推动数字基础设施与高端装备协同发展。",
                List.of("数字基础设施"),
                List.of("示例公司")
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM API Key");
    }

    @Test
    void resolvesKimiDefaultsToMoonshotOpenPlatform() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.ai.llm.provider", "kimi")
                .withProperty("research.ai.llm.api-key", "test-key");

        LlmTrendAnalysisService service = new LlmTrendAnalysisService(
                new TrendPromptService(),
                RestClient.create(),
                objectMapper,
                new LlmProperties(null, null, null, null, null, null, null, null, null, null),
                environment
        );

        LlmConfigPreview config = service.currentConfig();

        assertThat(config.provider()).isEqualTo("moonshot");
        assertThat(config.model()).isEqualTo("kimi-k2.6");
        assertThat(config.baseUrl()).isEqualTo("https://api.moonshot.ai/v1");
        assertThat(config.responseFormat()).isEqualTo("json_schema");
        assertThat(config.apiKeyConfigured()).isTrue();
        assertThat(config.apiKeySource()).isEqualTo("research.ai.llm.api-key");
    }

    @Test
    void resolvesExplicitKimiCodeProvider() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.ai.llm.provider", "kimi-code")
                .withProperty("research.ai.llm.api-key", "test-key");

        LlmTrendAnalysisService service = new LlmTrendAnalysisService(
                new TrendPromptService(),
                RestClient.create(),
                objectMapper,
                new LlmProperties(null, null, null, null, null, null, null, null, null, null),
                environment
        );

        LlmConfigPreview config = service.currentConfig();

        assertThat(config.provider()).isEqualTo("kimi-code");
        assertThat(config.model()).isEqualTo("kimi-for-coding");
        assertThat(config.baseUrl()).isEqualTo("https://api.kimi.com/coding/v1");
    }

    @Test
    void resolvesDeepSeekOpenPlatformDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.ai.llm.provider", "deepseek")
                .withProperty("research.ai.llm.api-key", "test-key");

        LlmTrendAnalysisService service = new LlmTrendAnalysisService(
                new TrendPromptService(),
                RestClient.create(),
                objectMapper,
                new LlmProperties(null, null, null, null, null, null, null, null, null, null),
                environment
        );

        LlmConfigPreview config = service.currentConfig();

        assertThat(config.provider()).isEqualTo("deepseek");
        assertThat(config.model()).isEqualTo("deepseek-v4-pro");
        assertThat(config.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(config.responseFormat()).isEqualTo("json_object");
        assertThat(config.apiKeyConfigured()).isTrue();
        assertThat(config.apiKeySource()).isEqualTo("research.ai.llm.api-key");
    }

    @Test
    void recoversJsonFromMarkdownFence() {
        LlmTrendAnalysisService service = serviceForParsing();

        JsonNode analysis = service.parseAnalysisContent("""
                ```json
                {"overall_assessment":{"summary":"韧性安全需求提升","confidence":72,"next_action":"进入公司池筛选"}}
                ```
                """, "deepseek");

        assertThat(analysis.path("overall_assessment").path("summary").asText()).isEqualTo("韧性安全需求提升");
    }

    @Test
    void recoversJsonAfterReasoningText() {
        LlmTrendAnalysisService service = serviceForParsing();

        JsonNode analysis = service.parseAnalysisContent("""
                下面是结构化结果：
                {"hidden_trends":[{"trend_name":"公共安全数字底座","logic_chain":["政策工具包含 {标准化}","需求转向监测预警"]}]}
                """, "deepseek");

        assertThat(analysis.path("hidden_trends").path(0).path("trend_name").asText()).isEqualTo("公共安全数字底座");
        assertThat(analysis.path("hidden_trends").path(0).path("logic_chain").path(0).asText()).contains("{标准化}");
    }

    private LlmTrendAnalysisService serviceForParsing() {
        return new LlmTrendAnalysisService(
                new TrendPromptService(),
                RestClient.create(),
                objectMapper,
                new LlmProperties(null, null, null, null, null, null, null, null, null, null),
                new MockEnvironment()
        );
    }
}
