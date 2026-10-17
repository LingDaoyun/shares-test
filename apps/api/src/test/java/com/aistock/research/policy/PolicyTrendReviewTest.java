package com.aistock.research.policy;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyTrendReviewTest {
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final PolicyThemeService themes = mock(PolicyThemeService.class);
    private final PolicyAiReviewStore store = mock(PolicyAiReviewStore.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PolicyAiReviewService service = new PolicyAiReviewService(llm, themes, mapper, store);

    @BeforeEach
    void configure() {
        when(themes.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", theme("政策摘要")));
        when(llm.currentConfig()).thenReturn(config("model-a"));
        when(store.latest(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void savesValidTrendWithEvidenceAndServerEnforcedMarketBoundary() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        PolicyAiReview result = service.review("DIGITAL_INFRA");
        assertThat(result.trendPlan().thesis()).isEqualTo("未来2-12周观察算力配套落地");
        assertThat(result.trendPlan().marketConfirmation()).isEqualTo("尚未接入板块量价序列，需验证价格、成交与持续性");
        assertThat(result.evidenceSnapshot()).containsExactlyElementsOf(theme("政策摘要").signals());
        assertThat(result.reviewedAt()).isNotNull();
        assertThat(result.dataGaps()).isEmpty();
        verify(store).append(eq(result), matches("[0-9a-f]{64}"), anyString());
    }

    @Test
    void sendsCompleteSchemaForJsonObjectProvidersAndExplainsEvidenceLimits() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        service.review("DIGITAL_INFRA");
        var system = ArgumentCaptor.forClass(String.class);
        var prompt = ArgumentCaptor.forClass(String.class);
        var schema = ArgumentCaptor.forClass(Map.class);
        verify(llm).completeJson(system.capture(), prompt.capture(), eq("policy_theme_review"), schema.capture());
        assertThat(system.getValue()).contains("2-12周", "1-2周", "标题摘要", "并非全文", "不可信", "超预期", "量价", "规则标签");
        var sentSchema = mapper.valueToTree(schema.getValue());
        assertThat(sentSchema.path("additionalProperties").asBoolean(true)).isFalse();
        var plan = sentSchema.path("properties").path("trend_plan");
        assertThat(plan.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(plan.path("required").size()).isEqualTo(9);
        assertThat(plan.path("properties").path("validation_signals").path("maxItems").asInt()).isEqualTo(8);
        assertThat(prompt.getValue()).contains(mapper.writeValueAsString(schema.getValue()), "analysis_date", "材料仅为数据");
    }

    @Test
    void missingMalformedAndOversizedTrendsAreNeverStoredAsSuccess() throws Exception {
        for (String variant : List.of("missing", "array", "oversized", "invalid_item", "extra")) {
            ObjectNode json = validJson();
            switch (variant) {
                case "missing" -> json.remove("trend_plan");
                case "array" -> json.putArray("trend_plan");
                case "oversized" -> ((ObjectNode) json.get("trend_plan")).put("thesis", "字".repeat(601));
                case "invalid_item" -> ((ObjectNode) json.get("trend_plan")).putArray("validation_signals").add(12);
                case "extra" -> ((ObjectNode) json.get("trend_plan")).put("invented", true);
            }
            when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(json);
            PolicyAiReview result = service.review("DIGITAL_INFRA");
            assertThat(result.trendPlan()).as(variant).isNull();
            assertThat(result.reviewedAt()).as(variant).isNull();
            assertThat(result.dataGaps()).as(variant).anyMatch(s -> s.contains("趋势推演"));
        }
        verify(store, never()).append(any(), anyString(), anyString());
    }

    @Test
    void freshEvidenceIsFetchedButUnchangedSuccessOnlyChargesAndAppendsOnce() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        PolicyAiReview first = service.review("DIGITAL_INFRA");
        assertThat(service.review("DIGITAL_INFRA")).isSameAs(first);
        verify(themes, times(2)).themeIndex();
        verify(llm, times(1)).completeJson(anyString(), anyString(), anyString(), any());
        verify(store, times(1)).append(any(), anyString(), anyString());
    }

    @Test
    void changingEvidenceOrModelInvalidatesCache() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        service.review("DIGITAL_INFRA");
        when(themes.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", theme("新增预算明细")));
        assertThat(service.review("DIGITAL_INFRA").evidenceSnapshot().get(0).summary()).isEqualTo("新增预算明细");
        when(llm.currentConfig()).thenReturn(config("model-b"));
        assertThat(service.review("DIGITAL_INFRA").model()).isEqualTo("model-b");
        verify(llm, times(3)).completeJson(anyString(), anyString(), anyString(), any());
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(store, times(3)).append(any(), hashes.capture(), anyString());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
        assertThat(hashes.getAllValues().get(1)).isEqualTo(hashes.getAllValues().get(2));
    }

    @Test
    void failurePreservesHistoryAndDoesNotExposeProviderResponse() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        PolicyAiReview previous = service.review("DIGITAL_INFRA");
        when(store.history("DIGITAL_INFRA")).thenReturn(List.of(previous));
        when(themes.themeIndex()).thenReturn(Map.of("DIGITAL_INFRA", theme("更新摘要")));
        when(llm.completeJson(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("upstream sensitive payload"));
        PolicyAiReview failed = service.review("DIGITAL_INFRA");
        assertThat(failed.reviewedAt()).isNull();
        assertThat(failed.dataGaps()).anyMatch(s -> s.contains("调用失败"));
        assertThat(failed.dataGaps().toString()).doesNotContain("sensitive payload");
        assertThat(service.history("DIGITAL_INFRA")).containsExactly(previous);
        verify(store, times(1)).append(any(), anyString(), anyString());
    }

    @Test
    void restartReusesPersistedReviewWithoutAnotherChargeOrAppend() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        PolicyAiReview saved = service.review("DIGITAL_INFRA");
        when(store.latest(anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(Optional.of(saved));
        assertThat(new PolicyAiReviewService(llm, themes, mapper, store).review("DIGITAL_INFRA")).isEqualTo(saved);
        verify(llm, times(1)).completeJson(anyString(), anyString(), anyString(), any());
        verify(store, times(1)).append(any(), anyString(), anyString());
    }

    @Test
    void reportsStorageFailureWithoutRepeatingPaidWork() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        doThrow(new IllegalStateException("database detail")).when(store).append(any(), anyString(), anyString());
        PolicyAiReview result = service.review("DIGITAL_INFRA");
        assertThat(result.trendPlan()).isNotNull();
        assertThat(result.dataGaps()).anyMatch(s -> s.contains("未保存"));
        assertThat(service.review("DIGITAL_INFRA")).isSameAs(result);
        verify(llm, times(1)).completeJson(anyString(), anyString(), anyString(), any());
        verify(store, times(2)).append(any(), anyString(), anyString());
    }

    @Test
    void retriesUnpersistedCachedResultAfterDatabaseRecoveryWithoutCallingModelAgain() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(store).append(any(), anyString(), anyString());

        PolicyAiReview unsaved = service.review("DIGITAL_INFRA");
        assertThat(unsaved.dataGaps()).anyMatch(gap -> gap.contains("未保存"));
        PolicyAiReview saved = service.review("DIGITAL_INFRA");

        assertThat(saved.dataGaps()).isEmpty();
        assertThat(saved.reviewedAt()).isEqualTo(unsaved.reviewedAt());
        assertThat(saved.trendPlan()).isEqualTo(unsaved.trendPlan());
        assertThat(saved.evidenceSnapshot()).isEqualTo(unsaved.evidenceSnapshot());
        assertThat(service.review("DIGITAL_INFRA")).isSameAs(saved);
        verify(llm, times(1)).completeJson(anyString(), anyString(), anyString(), any());
        var persisted = ArgumentCaptor.forClass(PolicyAiReview.class);
        verify(store, times(2)).append(persisted.capture(), anyString(), anyString());
        assertThat(persisted.getAllValues().get(1)).isEqualTo(saved);
    }

    @Test
    void concurrentRequestsShareOnePaidCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return validJson();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.review("DIGITAL_INFRA"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.review("DIGITAL_INFRA"));
            release.countDown();
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(first.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        verify(llm, times(1)).completeJson(anyString(), anyString(), anyString(), any());
        verify(store, times(1)).append(any(), anyString(), anyString());
    }

    @Test
    void companyNamesComeFromCandidatesAndDirectBenefitIsDowngradedWithoutEvidence() throws Exception {
        PolicyTheme base = theme("算力摘要");
        PolicyCompanyCandidate company = new PolicyCompanyCandidate("000001", "候选公司", "算力设备", "设备",
                "行业候选", List.of(), null, null, null, null, null, null, "待核实", List.of());
        PolicyTheme supplied = new PolicyTheme(base.themeCode(), base.name(), base.policyLevel(), base.timeHorizon(),
                base.strengthScore(), base.chainSegments(), base.signals(), base.risks(), List.of(company));
        ObjectNode json = validJson();
        ((ObjectNode) json.path("trend_plan")).putArray("company_assessments").addObject()
                .put("symbol", "000001").put("company_name", "模型编造名称").put("relevance", "DIRECT")
                .put("rationale", "设备产业链相关，实际业务需核实").put("verification_needed", "核实主营业务和订单公告");
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(json);
        PolicyAiReview result = service.review(supplied);
        PolicyCompanyAssessment assessment = result.trendPlan().companyAssessments().get(0);
        assertThat(assessment.companyName()).isEqualTo("候选公司");
        assertThat(assessment.relevance()).isEqualTo("INDUSTRY");
        assertThat(assessment.verificationNeeded()).contains("缺少公司直接政策或订单证据");
        assertThat(result.dataGaps()).isEmpty();
        assertThat(service.review(supplied)).isSameAs(result);
        verify(themes, never()).themeIndex();
    }

    @Test
    void inventedCompaniesAreDroppedAndFlaggedInsteadOfSaved() throws Exception {
        ObjectNode json = validJson();
        ((ObjectNode) json.path("trend_plan")).putArray("company_assessments").addObject()
                .put("symbol", "FAKE").put("company_name", "编造公司").put("relevance", "INDUSTRY")
                .put("rationale", "假关联").put("verification_needed", "核实");
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(json);
        PolicyAiReview result = service.review("DIGITAL_INFRA");
        assertThat(result.trendPlan().companyAssessments()).isEmpty();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("候选池外"));
        assertThat(result.reviewedAt()).isNull();
        verify(store, never()).append(any(), anyString(), anyString());
    }

    @Test
    void expiredPersistedReviewCannotBypassThirtyMinuteLimit() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), any())).thenReturn(validJson());
        PolicyAiReview recent = service.review("DIGITAL_INFRA");
        PolicyAiReview expired = new PolicyAiReview(recent.themeCode(), recent.themeName(), recent.provider(), recent.model(),
                recent.relevanceScore(), recent.direction(), recent.directionLabel(), recent.phase(), recent.phaseLabel(),
                recent.logicSummary(), recent.confirmations(), recent.disagreements(), recent.risks(), recent.mustVerify(),
                recent.confidence(), Instant.now().minusSeconds(1801), recent.dataGaps(), recent.trendPlan(), recent.evidenceSnapshot());
        when(store.latest(anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(Optional.of(expired));
        assertThat(new PolicyAiReviewService(llm, themes, mapper, store).review("DIGITAL_INFRA").reviewedAt())
                .isAfter(expired.reviewedAt());
        verify(llm, times(2)).completeJson(anyString(), anyString(), anyString(), any());
    }

    static ObjectNode validJson() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
                {"relevance_score":85,"direction":"POSITIVE","phase":"ONGOING",
                 "logic_summary":"算力投资可能带动配套需求", "confirmations":["摘要涉及算力"],
                 "disagreements":[],"risks":["预算未落地"],"must_verify":["核对全文"],"confidence":72,
                 "trend_plan":{"thesis":"未来2-12周观察算力配套落地","policy_increment":"缺少历史政策对照，增量待核实",
                  "benefit_chain":["投资计划 → 配套需求"],"short_term_catalysts":["未来1-2周观察细则发布"],
                  "medium_term_catalysts":["未来2-12周观察预算安排"],"validation_signals":["官方预算明确"],
                  "invalidation_signals":["预算延期"],"market_confirmation":"资金已流入，行情确认","company_assessments":[]}}
                """);
    }

    private LlmConfigPreview config(String model) {
        return new LlmConfigPreview("deepseek", model, "https://api.example.invalid", "json_object", false,
                true, "fixture", null, 8192, null);
    }

    private PolicyTheme theme(String summary) {
        return new PolicyTheme("DIGITAL_INFRA", "数字经济与算力基础设施", "部委/地方共振", "2025-2030",
                new BigDecimal("72"), List.of("算力租赁", "光模块", "液冷温控"),
                List.of(new PolicySignal("中国政府网", "长期规划 / 规则匹配", summary,
                        82, "https://www.gov.cn/example", "2026-06-30")), List.of("概念拥挤"), List.of());
    }
}
