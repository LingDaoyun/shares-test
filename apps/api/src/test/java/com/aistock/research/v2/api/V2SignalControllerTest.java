package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerRepository;
import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class V2SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsSampleSignalAndRecordsItInLedger() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/signals/sample")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("strategyCode", "CYCLE_REVERSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("002714"))
                .andExpect(jsonPath("$.strategyCode").value("CYCLE_REVERSAL"))
                .andExpect(jsonPath("$.strategyVersion").value("cycle-reversal-v2.0.0"))
                .andExpect(jsonPath("$.action").value("NEXT_WATCH"))
                .andExpect(jsonPath("$.rankScore").value(50.0))
                .andExpect(jsonPath("$.dataConfidence").value(40.0))
                .andExpect(jsonPath("$.ledgerId").isNotEmpty())
                .andExpect(jsonPath("$.decisionAt").isNotEmpty())
                .andExpect(jsonPath("$.dataCutoffAt").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String ledgerId = response.path("ledgerId").asText();
        List<V2RecommendationLedgerEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);

        V2RecommendationLedgerEntity ledger = repository.findById(ledgerId).orElseThrow();
        assertThat(ledger.getStrategyVersion()).isEqualTo("cycle-reversal-v2.0.0");
        assertThat(ledger.getDecisionAt()).isNotNull();
        assertThat(ledger.getDataCutoffAt()).isNotNull();

        JsonNode payload = objectMapper.readTree(ledger.getPayloadJson());
        assertThat(payload.path("sourceQuality").asText()).isEqualTo("SINGLE_SOURCE");
        assertThat(payload.path("signalProvenance").asText()).isEqualTo("RULE_ENGINE");
        assertThat(payload.path("replayPayload").isObject()).isTrue();
        assertThat(payload.path("replayPayload").path("sourceQuality").asText())
                .isEqualTo("SINGLE_SOURCE");
        assertThat(payload.path("context").path("source").asText())
                .isEqualTo("v2-compatibility-probe");
        assertThat(payload.path("replayPayload").path("source").asText())
                .isEqualTo("v2-compatibility-probe");
        assertThat(payload.path("replayPayload").path("sourceQualityReason").asText())
                .isEqualTo("compatibility probe");
    }
}
