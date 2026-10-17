package com.aistock.research.policy;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PolicyAiReviewServiceTest {

    private final LlmChatClient llmChatClient = mock(LlmChatClient.class);
    private final PolicyThemeService themeService = mock(PolicyThemeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PolicyAiReviewService service = new PolicyAiReviewService(llmChatClient, themeService, objectMapper);

    @Test
    void doesNotChargeForAThemeWithoutEvidence() {
        PolicyTheme original = digitalInfraTheme();
        when(themeService.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", new PolicyTheme(
                original.themeCode(), original.name(), original.policyLevel(), original.timeHorizon(),
                original.strengthScore(), original.chainSegments(), List.of(), original.risks(), original.companyPool())));
        when(llmChatClient.currentConfig()).thenReturn(config(true));

        PolicyAiReview review = service.review("DIGITAL_INFRA");

        assertThat(review.reviewedAt()).isNull();
        assertThat(review.dataGaps()).anyMatch(gap -> gap.contains("证据"));
        verify(llmChatClient, never()).completeJson(anyString(), anyString(), anyString(), any());
    }

    @Test
    void returnsUnconfiguredReviewWithoutCallingLlmWhenApiKeyMissing() {
        when(themeService.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", digitalInfraTheme()));
        when(llmChatClient.currentConfig()).thenReturn(config(false));

        PolicyAiReview review = service.review("DIGITAL_INFRA");

        assertThat(review.themeCode()).isEqualTo("DIGITAL_INFRA");
        assertThat(review.relevanceScore()).isNull();
        assertThat(review.dataGaps()).hasSize(1);
        assertThat(review.dataGaps().get(0)).contains("未配置大模型");
    }

    @Test
    void mapsStructuredAgentVerdictIntoReviewFields() throws Exception {
        when(themeService.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", digitalInfraTheme()));
        when(llmChatClient.currentConfig()).thenReturn(config(true));
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(objectMapper.readTree("""
                {
                  "relevance_score": 130,
                  "direction": "positive",
                  "phase": "ONGOING",
                  "logic_summary": "算力基建投资直接拉动上游光模块与液冷环节",
                  "confirmations": ["政策原文Agent命中确认"],
                  "disagreements": ["部分信号属于地方试点，不宜按全国推广解读"],
                  "risks": ["订单兑现节奏低于预期"],
                  "must_verify": ["核实文件编号与发布机关"],
                  "confidence": 78,
                  "trend_plan": {
                    "thesis":"观察未来几周算力配套落地", "policy_increment":"增量待核实",
                    "benefit_chain":["算力投资带动配套"],"short_term_catalysts":[],"medium_term_catalysts":[],
                    "validation_signals":["预算落地"],"invalidation_signals":["项目延期"],
                    "market_confirmation":"量价尚待观察", "company_assessments":[]
                  }
                }
                """));

        PolicyAiReview review = service.review("DIGITAL_INFRA");

        assertThat(review.provider()).isEqualTo("deepseek");
        assertThat(review.model()).isEqualTo("deepseek-v4-pro");
        assertThat(review.relevanceScore()).isEqualTo(100);
        assertThat(review.direction()).isEqualTo("POSITIVE");
        assertThat(review.directionLabel()).isEqualTo("政策利好");
        assertThat(review.phase()).isEqualTo("ONGOING");
        assertThat(review.phaseLabel()).isEqualTo("持续发酵");
        assertThat(review.logicSummary()).contains("算力基建");
        assertThat(review.confirmations()).containsExactly("政策原文Agent命中确认");
        assertThat(review.disagreements()).hasSize(1);
        assertThat(review.mustVerify()).containsExactly("核实文件编号与发布机关");
        assertThat(review.confidence()).isEqualTo(78);
        assertThat(review.dataGaps()).isEmpty();
        assertThat(review.reviewedAt()).isNotNull();
    }

    @Test
    void reportsDataGapsWhenAgentReturnsInvalidEnums() throws Exception {
        when(themeService.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", digitalInfraTheme()));
        when(llmChatClient.currentConfig()).thenReturn(config(true));
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(objectMapper.readTree("""
                {
                  "relevance_score": 66,
                  "direction": "SIDEWAYS",
                  "phase": "",
                  "logic_summary": "  ",
                  "confirmations": [],
                  "disagreements": [],
                  "risks": [],
                  "must_verify": [],
                  "confidence": 55
                }
                """));

        PolicyAiReview review = service.review("DIGITAL_INFRA");

        assertThat(review.direction()).isNull();
        assertThat(review.directionLabel()).isEqualTo("方向待定");
        assertThat(review.phaseLabel()).isEqualTo("阶段待定");
        assertThat(review.logicSummary()).isNull();
        assertThat(review.dataGaps()).hasSize(4);
    }

    @Test
    void degradesToUnavailableWhenLlmCallFails() {
        when(themeService.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", digitalInfraTheme()));
        when(llmChatClient.currentConfig()).thenReturn(config(true));
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("LLM 调用失败(provider=deepseek): HTTP 429 too many requests"));

        PolicyAiReview review = service.review("DIGITAL_INFRA");

        assertThat(review.relevanceScore()).isNull();
        assertThat(review.dataGaps()).hasSize(1);
        assertThat(review.dataGaps().get(0)).contains("AI 复核调用失败");
    }

    @Test
    void rejectsUnknownThemeCode() {
        when(themeService.themeIndex()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.review("UNKNOWN"))
                .isInstanceOf(NoSuchElementException.class);
    }

    private LlmConfigPreview config(boolean apiKeyConfigured) {
        return new LlmConfigPreview(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                "json_object",
                false,
                apiKeyConfigured,
                apiKeyConfigured ? "env:DEEPSEEK_API_KEY" : "missing",
                null,
                8192,
                null
        );
    }

    private PolicyTheme digitalInfraTheme() {
        return new PolicyTheme(
                "DIGITAL_INFRA",
                "数字经济与算力基础设施",
                "部委/地方共振",
                "2025-2030",
                new BigDecimal("72"),
                List.of("算力租赁", "光模块", "液冷温控"),
                List.of(new PolicySignal(
                        "中国政府网",
                        "长期规划 / 政策原文Agent命中+时效Agent确认",
                        "数字经济发展规划提及算力基础设施适度超前布局",
                        82,
                        "https://www.gov.cn/example",
                        "2026-06-30"
                )),
                List.of("概念拥挤"),
                List.of()
        );
    }
}
