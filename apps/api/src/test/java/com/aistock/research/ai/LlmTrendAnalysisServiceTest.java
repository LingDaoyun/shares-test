package com.aistock.research.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmTrendAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksWhenApiKeyIsMissing() {
        LlmTrendAnalysisService service = serviceWith(settings(null, "model-a"));

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
    void currentConfigUsesTheLatestProviderSnapshot() {
        LlmSettingsProvider provider = mock(LlmSettingsProvider.class);
        when(provider.current())
                .thenReturn(settings("test-key", "model-a"))
                .thenReturn(settings("test-key", "model-b"));
        LlmTrendAnalysisService service = new LlmTrendAnalysisService(
                new TrendPromptService(), objectMapper, provider);

        assertThat(service.currentConfig().model()).isEqualTo("model-a");
        assertThat(service.currentConfig().model()).isEqualTo("model-b");
    }

    @Test
    void recoversJsonFromMarkdownFence() {
        LlmTrendAnalysisService service = serviceWith(settings(null, "model-a"));

        JsonNode analysis = service.parseAnalysisContent("""
                ```json
                {"overall_assessment":{"summary":"韧性安全需求提升","confidence":72,"next_action":"进入公司池筛选"}}
                ```
                """, "deepseek");

        assertThat(analysis.path("overall_assessment").path("summary").asText())
                .isEqualTo("韧性安全需求提升");
    }

    @Test
    void recoversJsonAfterReasoningText() {
        LlmTrendAnalysisService service = serviceWith(settings(null, "model-a"));

        JsonNode analysis = service.parseAnalysisContent("""
                下面是结构化结果：
                {"hidden_trends":[{"trend_name":"公共安全数字底座","logic_chain":["政策工具包含 {标准化}","需求转向监测预警"]}]}
                """, "deepseek");

        assertThat(analysis.path("hidden_trends").path(0).path("trend_name").asText())
                .isEqualTo("公共安全数字底座");
        assertThat(analysis.path("hidden_trends").path(0).path("logic_chain").path(0).asText())
                .contains("{标准化}");
    }

    private LlmTrendAnalysisService serviceWith(LlmSettings settings) {
        LlmSettingsProvider provider = mock(LlmSettingsProvider.class);
        when(provider.current()).thenReturn(settings);
        return new LlmTrendAnalysisService(new TrendPromptService(), objectMapper, provider);
    }

    private LlmSettings settings(String key, String model) {
        return new LlmSettings(
                "deepseek",
                key,
                key == null ? "missing" : "database",
                model,
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null
        );
    }
}
