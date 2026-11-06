package com.aistock.research.hotspot;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.integration.news.IndustryNewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.aistock.research.hotspot.HotspotTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotspotAnalysisServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final HotspotCompanyClient companies = mock(HotspotCompanyClient.class);
    private final Instant now = Instant.parse("2026-10-19T02:00:00Z");
    private final IndustryNewsItem source = news("source-1", now.minusSeconds(3600));
    private HotspotAnalysisService service;

    @BeforeEach
    void setup() {
        when(llm.currentConfig()).thenReturn(config(true, "test-model"));
        service = new HotspotAnalysisService(llm, mapper, companies);
    }

    @Test
    void missingModelConfigurationKeepsNewsWithoutInventingRecommendations() {
        when(llm.currentConfig()).thenReturn(config(false, "test-model"));

        var result = service.analyze(List.of(source), now);

        assertThat(result.analyzed()).isFalse();
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("待研判");
            assertThat(event.sources()).containsExactly(source);
            assertThat(event.recommendations()).isEmpty();
        });
        verify(llm, never()).completeJson(anyString(), anyString(), anyString(), anyMap());
        verifyNoInteractions(companies);
    }

    @Test
    void rejectsUnverifiedCompanyAndOutOfEventNewsAndFabricatedBusinessQuotes() throws Exception {
        setupDiscovery();
        var unverified = recommendation("600999", "NEWS_MENTION", "source-1", "");
        var wrongNews = recommendation("600001", "BUSINESS_MATCH", "unknown-source", "研发和生产电池材料");
        var fabricated = recommendation("600001", "BUSINESS_MATCH", "source-1", "该公司获得大额采购订单");
        review(unverified, wrongNews, fabricated);

        var result = service.analyze(List.of(source), now);

        assertThat(result.events()).singleElement().satisfies(event -> assertThat(event.recommendations()).isEmpty());
        assertThat(result.dataGaps()).anyMatch(gap -> gap.startsWith("3 条模型股票建议"));
    }

    @Test
    void publishesOnlyExactBusinessEvidenceUsingVerifiedIdentityAndOriginalQuoteTime() throws Exception {
        setupDiscovery();
        ObjectNode valid = recommendation("600001", "BUSINESS_MATCH", "source-1", "研发和生产电池材料");
        valid.put("name", "模型编造的名称");
        review(valid, valid.deepCopy());

        var result = service.analyze(List.of(source), now);

        assertThat(result.analyzed()).isTrue();
        assertThat(result.events().get(0).recommendations()).singleElement().satisfies(stock -> {
            assertThat(stock.name()).isEqualTo("样本公司");
            assertThat(stock.evidence()).containsExactly(source);
            assertThat(stock.companyEvidence()).singleElement().satisfies(evidence ->
                    assertThat(evidence.excerpt()).isEqualTo("研发和生产电池材料"));
            assertThat(stock.firstSeenPrice()).isEqualByComparingTo("12.50");
            assertThat(stock.firstSeenQuoteAsOf()).isEqualTo(now.minusSeconds(1800).toString());
            assertThat(stock.risks()).anyMatch(risk -> risk.contains("不能确认本次事件已带来订单或收入"));
        });
    }

    @Test
    void industryInferenceIsForcedLowAndCannotMasqueradeAsBusinessMatch() throws Exception {
        setupDiscovery();
        ObjectNode invalid = recommendation("600001", "BUSINESS_MATCH", "source-1", "电池");
        ((ObjectNode) invalid.withArray("companyEvidence").get(0)).put("index", 1);
        ObjectNode inference = invalid.deepCopy();
        inference.put("relationType", "INDUSTRY_INFERENCE");
        inference.put("confidence", "HIGH");
        review(invalid, inference);

        var result = service.analyze(List.of(source), now);

        assertThat(result.events().get(0).recommendations()).singleElement().satisfies(stock -> {
            assertThat(stock.confidence()).isEqualTo("LOW");
            assertThat(stock.risks()).anyMatch(risk -> risk.contains("尚无具体产品、客户或订单证据"));
        });
        assertThat(result.dataGaps()).anyMatch(gap -> gap.startsWith("1 条模型股票建议"));
    }

    @Test
    void newsMentionRequiresVerifiedNameOrStandaloneCodeInOriginalArticle() throws Exception {
        setupDiscovery();
        review(recommendation("600001", "NEWS_MENTION", "source-1", ""));
        IndustryNewsItem unrelated = new IndustryNewsItem("source-1", "新型电池发布", "来源", source.url(),
                source.publishedAt(), "编号16000012不是证券代码，公司名称未披露");

        assertThat(service.analyze(List.of(unrelated), now).events().get(0).recommendations()).isEmpty();
        assertThat(service.analyze(List.of(source), now).events().get(0).recommendations()).hasSize(1);
    }

    @Test
    void reviewFailureKeepsSourcedEventsAndDoesNotMarkAnalysisReusable() throws Exception {
        setupDiscovery();
        when(llm.completeJson(anyString(), anyString(), eq("industry_hotspot_recommendations"), anyMap()))
                .thenThrow(new IllegalStateException("upstream unavailable"));

        var result = service.analyze(List.of(source), now);

        assertThat(result.analyzed()).isFalse();
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.sources()).containsExactly(source);
            assertThat(event.recommendations()).isEmpty();
        });
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("股票证据复核未完成"));
    }

    @Test
    void modelChangesInvalidateReuseIdentity() {
        String original = service.modelIdentity();
        when(llm.currentConfig()).thenReturn(config(true, "new-model"));
        assertThat(service.modelIdentity()).isNotEqualTo(original);
    }

    private void setupDiscovery() throws Exception {
        var discovery = mapper.readTree("""
                {"events":[{"title":"电池材料突破","eventType":"技术突破","industries":["电池"],
                "products":["电池材料"],"summary":"公开测试结果","catalyst":"需求待验证","heatScore":140,
                "confidence":"MEDIUM","sourceIds":["source-1"],"risks":[],
                "candidateStocks":[{"symbol":"600001","name":"样本公司"}]}]}
                """);
        when(llm.completeJson(anyString(), anyString(), eq("industry_hotspot_discovery"), anyMap())).thenReturn(discovery);
        var company = new HotspotCompanyClient.Company(quote("600001", "样本公司", "12.50", now.minusSeconds(1800)), "电池",
                List.of(new EvidenceItem("公司业务资料", "主营业务", "研发和生产电池材料", "https://company.example/600001", 75),
                        new EvidenceItem("行业分类", "行业归属", "电池", "https://company.example/600001", 65)));
        when(companies.fetch(anyList(), anyList())).thenReturn(Map.of("600001", company));
    }

    private ObjectNode recommendation(String symbol, String relation, String sourceId, String evidence) {
        ObjectNode stock = mapper.createObjectNode();
        stock.put("symbol", symbol).put("relationType", relation).put("recommendationReason", "公司业务具有相关性")
                .put("benefitMechanism", "需求增长后才可能兑现").put("confidence", "HIGH");
        stock.putArray("sourceIds").add(sourceId);
        stock.putArray("risks");
        var references = stock.putArray("companyEvidence");
        if (!evidence.isBlank()) references.addObject().put("index", 0).put("quote", evidence);
        return stock;
    }

    private void review(ObjectNode... stocks) {
        ObjectNode response = mapper.createObjectNode();
        var event = response.putArray("events").addObject();
        event.put("eventId", UUID.nameUUIDFromBytes(source.id().getBytes(StandardCharsets.UTF_8)).toString());
        var recommendations = event.putArray("recommendations");
        for (var stock : stocks) recommendations.add(stock);
        when(llm.completeJson(anyString(), anyString(), eq("industry_hotspot_recommendations"), anyMap())).thenReturn(response);
    }

    private LlmConfigPreview config(boolean configured, String model) {
        return new LlmConfigPreview("test", model, "https://model.example", "json_schema", true,
                configured, "test", null, 2000, 0.2);
    }
}
