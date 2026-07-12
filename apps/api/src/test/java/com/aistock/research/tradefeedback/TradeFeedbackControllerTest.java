package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class TradeFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TradeCaseRepository caseRepository;

    @Autowired
    private TradeFillRepository fillRepository;

    @Autowired
    private TradeFillRevisionRepository fillRevisionRepository;

    @Autowired
    private RecommendationAttestationService attestationService;

    @SpyBean
    private TradeOutcomeRepository outcomeRepository;

    @MockBean
    private TradeMarketDataGateway marketDataGateway;

    @AfterEach
    void cleanJournal() {
        outcomeRepository.deleteAll();
        fillRevisionRepository.deleteAll();
        fillRepository.deleteAll();
        caseRepository.deleteAll();
    }

    @Test
    void refreshesAndReturnsOutcomeViewsWithLivePriceLedgerAndPendingNulls() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":100}
                                """))
                .andExpect(status().isCreated());
        when(marketDataGateway.dailyKLineSeries(anyString(), any(), any())).thenReturn(
                MarketKLineSeries.complete(java.util.List.of(
                        bar("2026-07-14", "37", "38", "35"),
                        bar("2026-07-15", "38", "39", "36"),
                        bar("2026-07-16", "39", "40", "37"),
                        bar("2026-07-17", "40", "41", "38"),
                        bar("2026-07-20", "41", "42", "39")), "TEST_DAILY_KLINE"));
        when(marketDataGateway.latestPrice("002714")).thenReturn(java.util.Optional.of(
                new LatestMarketPrice(new java.math.BigDecimal("40"), "EAST_MONEY_LIVE_QUOTE",
                        java.time.LocalDate.parse("2026-07-20"),
                        java.time.Instant.parse("2026-07-20T07:00:00Z"))));
        java.time.Instant versionBeforeRefresh = caseRepository.findById(caseId).orElseThrow().getUpdatedAt();

        String refreshed = mockMvc.perform(post("/api/trade-cases/{caseId}/refresh", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeWarnings").isEmpty())
                .andExpect(jsonPath("$.ledger.latestPrice").value(40))
                .andExpect(jsonPath("$.ledger.averageCost").value(35.2))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'RECOMMENDATION' && @.horizon == 'T1')].status")
                        .value("MATURED"))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'RECOMMENDATION' && @.horizon == 'T20')].status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'RECOMMENDATION' && @.horizon == 'T20')].returnPct")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'EXECUTION' && @.horizon == 'CURRENT')].returnPct")
                        .value(13.6364))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'RECOMMENDATION' && @.horizon == 'CURRENT')].sourceName")
                        .value("EAST_MONEY_LIVE_QUOTE"))
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'RECOMMENDATION' && @.horizon == 'CURRENT')].marketTimestamp")
                        .value("2026-07-20T07:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        String refreshedVersion = new com.fasterxml.jackson.databind.ObjectMapper().readTree(refreshed)
                .path("updatedAt").asText();
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(refreshedVersion))
                .isAfter(versionBeforeRefresh);
        mockMvc.perform(get("/api/trade-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.caseId == '" + caseId + "')].updatedAt").value(refreshedVersion));

        mockMvc.perform(post("/api/trade-cases/{caseId}/refresh", caseId))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(outcomeRepository.findByCaseIdOrderByHorizonAsc(caseId))
                .hasSize(6);
    }

    @Test
    void createsListsAndLoadsTradeCasesWithRecommendationPayloadAndLedger() throws Exception {
        String request = attestedRequest(java.util.Map.of("source", "test"));

        String created = mockMvc.perform(post("/api/trade-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").isNotEmpty())
                .andExpect(jsonPath("$.decisionId").doesNotExist())
                .andExpect(jsonPath("$.recommendationVerified").value(true))
                .andExpect(jsonPath("$.recommendationPayload.source").value("test"))
                .andExpect(jsonPath("$.ledger.latestPrice").doesNotExist())
                .andExpect(jsonPath("$.ledger.unrealizedProfit").doesNotExist())
                .andExpect(jsonPath("$.fills").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String caseId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("caseId").asText();

        mockMvc.perform(get("/api/trade-cases").param("status", "planned").param("symbol", "002714"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value(caseId))
                .andExpect(jsonPath("$[0].recommendationAction").value("分批建仓"))
                .andExpect(jsonPath("$[0].status").value("PLANNED"))
                .andExpect(jsonPath("$[0].ledger.positionQuantity").value(0));

        mockMvc.perform(get("/api/trade-cases/{caseId}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void listsCompactOutcomesWithOneBulkQueryAndNoPerCaseOutcomeReads() throws Exception {
        String firstCaseId = createCase();
        caseRepository.save(TradeCaseEntity.planned(
                "case-2", "fingerprint-2", null, "600519", "贵州茅台", "SHORT_TERM",
                "观察", null, "short-term-v1", new java.math.BigDecimal("1500"),
                java.time.Instant.parse("2026-07-13T02:00:00Z"), "{}",
                java.time.Instant.parse("2026-07-13T02:00:00Z")));
        outcomeRepository.save(TradeOutcomeEntity.matured(
                "outcome-list-t1", firstCaseId, "RECOMMENDATION", "T1",
                new java.math.BigDecimal("36.20"), new java.math.BigDecimal("37.00"),
                java.time.LocalDate.parse("2026-07-14"), new java.math.BigDecimal("2.2099"),
                null, null, "DAILY_KLINE", null, java.time.Instant.parse("2026-07-14T08:00:00Z")));
        outcomeRepository.save(TradeOutcomeEntity.matured(
                "outcome-list-current", firstCaseId, "RECOMMENDATION", "CURRENT",
                new java.math.BigDecimal("36.20"), new java.math.BigDecimal("38.00"),
                java.time.LocalDate.parse("2026-07-14"), new java.math.BigDecimal("4.9724"),
                null, null, "EAST_MONEY_LIVE_QUOTE", java.time.Instant.parse("2026-07-14T07:00:00Z"),
                java.time.Instant.parse("2026-07-14T08:00:00Z")));
        outcomeRepository.save(TradeOutcomeEntity.pending(
                "outcome-list-t5", "case-2", "RECOMMENDATION", "T5",
                java.time.Instant.parse("2026-07-14T08:00:00Z")));
        clearInvocations(outcomeRepository);

        mockMvc.perform(get("/api/trade-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.caseId == '" + firstCaseId + "')].outcomes[?(@.horizon == 'T1')].status").value("MATURED"))
                .andExpect(jsonPath("$[?(@.caseId == '" + firstCaseId + "')].ledger.latestPrice").value(38.0))
                .andExpect(jsonPath("$[?(@.caseId == 'case-2')].outcomes[0].status").value("PENDING"));

        verify(outcomeRepository, times(1)).findByCaseIdInOrderByCaseIdAscBaselineTypeAscHorizonAsc(any());
        verify(outcomeRepository, never()).findByCaseIdOrderByHorizonAsc(anyString());
    }

    @Test
    void addsUpdatesAndDeletesFillsInExecutionOrder() throws Exception {
        String caseId = createCase();

        String firstFill = mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":200}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HOLDING"))
                .andExpect(jsonPath("$.ledger.positionQuantity").value(200))
                .andExpect(jsonPath("$.fills[0].side").value("BUY"))
                .andReturn().getResponse().getContentAsString();
        String fillId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstFill)
                .path("fills").get(0).path("fillId").asText();

        mockMvc.perform(put("/api/trade-cases/{caseId}/fills/{fillId}", caseId, fillId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.50,"quantity":150}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledger.positionQuantity").value(150))
                .andExpect(jsonPath("$.fills[0].price").value(35.50));

        mockMvc.perform(delete("/api/trade-cases/{caseId}/fills/{fillId}", caseId, fillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.fills").isEmpty());
    }

    @Test
    void hidesStaleExecutionOutcomesImmediatelyAfterAFillCorrection() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":100}
                                """))
                .andExpect(status().isCreated());
        String sellResponse = mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","executedAt":"2026-07-11T02:35:00Z","price":38.00,"quantity":100}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sellId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(sellResponse)
                .path("fills").get(1).path("fillId").asText();
        when(marketDataGateway.dailyKLineSeries(anyString(), any(), any())).thenReturn(
                MarketKLineSeries.complete(java.util.List.of(
                        bar("2026-07-11", "36", "39", "34")), "TEST_DAILY_KLINE"));
        when(marketDataGateway.latestPrice(anyString())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/trade-cases/{caseId}/refresh", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'EXECUTION' && @.horizon == 'CLOSED')].status")
                        .value("MATURED"));

        mockMvc.perform(put("/api/trade-cases/{caseId}/fills/{fillId}", caseId, sellId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","executedAt":"2026-07-11T02:35:00Z","price":33.00,"quantity":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[?(@.baselineType == 'EXECUTION')]").isEmpty())
                .andExpect(jsonPath("$.outcomeWarnings[0]").value(containsString("等待重新计算")));

        org.assertj.core.api.Assertions.assertThat(caseRepository.findById(caseId).orElseThrow().isOutcomeDirty())
                .isTrue();
    }

    @Test
    void returnsBadRequestForOversellAndMalformedFillInput() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":200}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","executedAt":"2026-07-11T02:35:00Z","price":36,"quantity":201}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("超过当前持仓")));

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T02:35:00Z","price":0,"quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.price").isNotEmpty())
                .andExpect(jsonPath("$.fields.quantity").isNotEmpty());
    }

    @Test
    void returnsFieldAwareBadRequestForUnknownTradeSide() throws Exception {
        String caseId = createCase();

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"HOLD","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"))
                .andExpect(jsonPath("$.fields.side").isNotEmpty());
    }

    @Test
    void returnsFieldAwareBadRequestForMalformedExecutedAt() throws Exception {
        String caseId = createCase();

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"not-an-instant","price":35.20,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"))
                .andExpect(jsonPath("$.fields.executedAt").isNotEmpty());
    }

    @Test
    void filtersCaseInsensitivelyAndMapsMissingAndInvalidTransitions() throws Exception {
        String caseId = createCase();

        mockMvc.perform(get("/api/trade-cases").param("status", "planned").param("symbol", "002714"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value(caseId));
        mockMvc.perform(get("/api/trade-cases/not-found"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/trade-cases/{caseId}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-11T01:35:00Z","price":35.20,"quantity":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trade-cases/{caseId}/cancel", caseId))
                .andExpect(status().isConflict());
    }

    @Test
    void pagesCasesWithAStableCreatedAtAndCaseIdCursor() throws Exception {
        caseRepository.save(TradeCaseEntity.planned(
                "case-old", "fingerprint-old", null, "002714", "牧原股份", "MISPRICING",
                "观察", null, "mispricing-v2", new java.math.BigDecimal("35"),
                java.time.Instant.parse("2026-07-01T01:00:00Z"), "{}",
                java.time.Instant.parse("2026-07-01T01:00:00Z")));
        caseRepository.save(TradeCaseEntity.planned(
                "case-new", "fingerprint-new", null, "600519", "贵州茅台", "SHORT_TERM",
                "观察", null, "short-term-right-side-v2", new java.math.BigDecimal("1500"),
                java.time.Instant.parse("2026-07-02T01:00:00Z"), "{}",
                java.time.Instant.parse("2026-07-02T01:00:00Z")));

        String firstPage = mockMvc.perform(get("/api/trade-cases").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value("case-new"))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode cursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstPage).get(0);

        mockMvc.perform(get("/api/trade-cases")
                        .param("limit", "1")
                        .param("beforeCreatedAt", cursor.path("createdAt").asText())
                        .param("beforeCaseId", cursor.path("caseId").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value("case-old"));
    }

    @Test
    void validatesCreateRequestBeforeItReachesPersistence() throws Exception {
        mockMvc.perform(post("/api/trade-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"attestationToken":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.attestationToken").isNotEmpty());
    }

    @Test
    void returnsAClearServiceErrorWhenPersistedPayloadCannotBeParsed() throws Exception {
        caseRepository.save(TradeCaseEntity.planned(
                "case-invalid-payload", "fingerprint-invalid-payload", null, "002714", "牧原股份", "MISPRICING",
                "分批建仓", null, "mispricing-v2", new java.math.BigDecimal("36.20"),
                java.time.Instant.parse("2026-07-13T01:00:00Z"), "not-json", java.time.Instant.parse("2026-07-13T01:00:00Z")
        ));

        mockMvc.perform(get("/api/trade-cases/case-invalid-payload"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("已保存的推荐载荷无法解析"));
    }

    private String createCase() throws Exception {
        String response = mockMvc.perform(post("/api/trade-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attestedRequest(java.util.Map.of())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("caseId").asText();
    }

    private String attestedRequest(Object payload) throws Exception {
        String token = attestationService.register(
                RecommendationSource.MISPRICING,
                "002714",
                "牧原股份",
                "分批建仓",
                new java.math.BigDecimal("78"),
                new java.math.BigDecimal("36.20"),
                java.time.Instant.parse("2026-07-10T01:00:00Z"),
                payload);
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("attestationToken", token));
    }

    private MarketBar bar(String date, String close, String high, String low) {
        return new MarketBar(
                java.time.LocalDate.parse(date),
                new java.math.BigDecimal(close),
                new java.math.BigDecimal(high),
                new java.math.BigDecimal(low));
    }
}
