package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    private TradeOutcomeRepository outcomeRepository;

    @MockBean
    private TradeMarketDataGateway marketDataGateway;

    @AfterEach
    void cleanJournal() {
        outcomeRepository.deleteAll();
        fillRepository.deleteAll();
        caseRepository.deleteAll();
    }

    @Test
    void refreshesAndReturnsOutcomeViewsWithLivePriceLedgerAndPendingNulls() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":100}
                                """))
                .andExpect(status().isCreated());
        when(marketDataGateway.dailyKLines(anyString(), any(), any())).thenReturn(java.util.List.of(
                bar("2026-07-14", "37", "38", "35"),
                bar("2026-07-15", "38", "39", "36"),
                bar("2026-07-16", "39", "40", "37"),
                bar("2026-07-17", "40", "41", "38"),
                bar("2026-07-20", "41", "42", "39")));
        when(marketDataGateway.latestPrice("002714")).thenReturn(java.util.Optional.of(
                new LatestMarketPrice(new java.math.BigDecimal("40"), "EAST_MONEY_LIVE_QUOTE",
                        java.time.LocalDate.parse("2026-07-20"),
                        java.time.Instant.parse("2026-07-20T07:00:00Z"))));

        mockMvc.perform(post("/api/trade-cases/{caseId}/refresh", caseId))
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
                        .value("2026-07-20T07:00:00Z"));

        mockMvc.perform(post("/api/trade-cases/{caseId}/refresh", caseId))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(outcomeRepository.findByCaseIdOrderByHorizonAsc(caseId))
                .hasSize(6);
    }

    @Test
    void createsListsAndLoadsTradeCasesWithRecommendationPayloadAndLedger() throws Exception {
        String request = """
                {
                  "symbol":"002714",
                  "companyName":"牧原股份",
                  "sourceModule":"MISPRICING",
                  "recommendationAction":"分批建仓",
                  "recommendationScore":78,
                  "ruleVersion":"mispricing-v2",
                  "recommendedPrice":36.20,
                  "recommendedAt":"2026-07-13T01:00:00Z",
                  "recommendationPayload":{"source":"test"}
                }
                """;

        String created = mockMvc.perform(post("/api/trade-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").isNotEmpty())
                .andExpect(jsonPath("$.decisionId").doesNotExist())
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
    void addsUpdatesAndDeletesFillsInExecutionOrder() throws Exception {
        String caseId = createCase();

        String firstFill = mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":200}
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
                                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.50,"quantity":150}
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
    void returnsBadRequestForOversellAndMalformedFillInput() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":200}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","executedAt":"2026-07-13T02:35:00Z","price":36,"quantity":201}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("超过当前持仓")));

        mockMvc.perform(post("/api/trade-cases/{id}/fills", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","executedAt":"2026-07-13T02:35:00Z","price":0,"quantity":0}
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
                                {"side":"HOLD","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":1}
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
                                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trade-cases/{caseId}/cancel", caseId))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesCreateRequestBeforeItReachesPersistence() throws Exception {
        mockMvc.perform(post("/api/trade-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"bad","companyName":"","sourceModule":"","recommendationAction":"","ruleVersion":"","recommendedPrice":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.symbol").isNotEmpty())
                .andExpect(jsonPath("$.fields.recommendedAt").isNotEmpty());
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
                        .content("""
                                {
                                  "symbol":"002714",
                                  "companyName":"牧原股份",
                                  "sourceModule":"MISPRICING",
                                  "recommendationAction":"分批建仓",
                                  "ruleVersion":"mispricing-v2",
                                  "recommendedPrice":36.20,
                                  "recommendedAt":"2026-07-13T01:00:00Z",
                                  "recommendationPayload":{}
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("caseId").asText();
    }

    private MarketBar bar(String date, String close, String high, String low) {
        return new MarketBar(
                java.time.LocalDate.parse(date),
                new java.math.BigDecimal(close),
                new java.math.BigDecimal(high),
                new java.math.BigDecimal(low));
    }
}
