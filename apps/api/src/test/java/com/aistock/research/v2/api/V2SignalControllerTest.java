package com.aistock.research.v2.api;

import com.aistock.research.trading.TradingClockService;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.aistock.research.v2.decision.V2RecommendationLedgerRepository;
import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class V2SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecommendationAttestationService attestationService;

    @MockBean
    private TradingClockService tradingClockService;

    @BeforeEach
    void openAuthoritativeDecisionWindow() {
        when(tradingClockService.shortTermDecisionCheckpoint()).thenReturn("TAIL_ENTRY_1445_1456");
    }

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
                .andExpect(jsonPath("$.sourceQuality").value("SINGLE_SOURCE"))
                .andExpect(jsonPath("$.signalProvenance").value("COMPATIBILITY_PROBE"))
                .andExpect(jsonPath("$.replayPayload.source").value("v2-compatibility-probe"))
                .andExpect(jsonPath("$.replayPayload.sourceQualityReason").value("compatibility probe"))
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
        assertThat(payload.path("signalProvenance").asText()).isEqualTo("COMPATIBILITY_PROBE");
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

    @Test
    void returnsStrategyBundleWithLongShortValidationAndAgentEvidence() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/signals/strategy-bundle")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("goldenCrossState", "CONFIRMED")
                        .param("goldenCrossTradingDays", "1")
                        .param("goldenCrossPriorityTier", "3")
                        .param("recommendationToken", verifiedShortTermToken(
                                "RIGHT_EARLY_ADD", "ADD", "CONFIRMED", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("002714"))
                .andExpect(jsonPath("$.companyName").value("牧原股份"))
                .andExpect(jsonPath("$.longTermSignals.length()").value(3))
                .andExpect(jsonPath("$.longTermSignals[0].strategyCode").value("VALUE_REVERSION"))
                .andExpect(jsonPath("$.longTermSignals[1].strategyCode").value("QUALITY_COMPOUNDER"))
                .andExpect(jsonPath("$.longTermSignals[2].strategyCode").value("CYCLE_REVERSAL"))
                .andExpect(jsonPath("$.longTermSignals[2].action").value("ADD"))
                .andExpect(jsonPath("$.shortRightSideSignal.strategyCode").value("SHORT_RIGHT_SIDE"))
                .andExpect(jsonPath("$.shortRightSideSignal.strategyVersion").value("short-right-side-v2.1.1"))
                .andExpect(jsonPath("$.shortRightSideSignal.action").value("ADD"))
                .andExpect(jsonPath("$.shortRightSideSignal.context.validationStatus").value("PASSED_OOS"))
                .andExpect(jsonPath("$.shortRightSideSignal.historicalHitRate").value(57.5))
                .andExpect(jsonPath("$.shortRightSideSignal.replayPayload.goldenCross.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.shortRightSideSignal.replayPayload.goldenCross.tradingDays").value(1))
                .andExpect(jsonPath("$.shortRightSideSignal.replayPayload.goldenCross.priorityTier").value(3))
                .andExpect(jsonPath("$.agentEvidenceReview.findings.length()").value(3))
                .andExpect(jsonPath("$.agentEvidenceReview.supportCount").value(2))
                .andExpect(jsonPath("$.agentEvidenceReview.abstainCount").value(1))
                .andExpect(jsonPath("$.agentEvidenceReview.warnings[0]").value("估值复核 Agent 缺少可核验证据，已强制弃权。"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("longTermSignals").findValuesAsText("ledgerId"))
                .allSatisfy(ledgerId -> assertThat(ledgerId).isNotBlank());
        assertThat(response.path("shortRightSideSignal").path("ledgerId").asText()).isNotBlank();

        List<V2RecommendationLedgerEntity> rows = repository.findAll();
        assertThat(rows).hasSize(4);
        assertThat(rows)
                .extracting(V2RecommendationLedgerEntity::getStrategyCode)
                .containsExactlyInAnyOrder("VALUE_REVERSION", "QUALITY_COMPOUNDER",
                        "CYCLE_REVERSAL", "SHORT_RIGHT_SIDE");
        assertThat(rows)
                .filteredOn(row -> row.getStrategyCode().equals("SHORT_RIGHT_SIDE"))
                .singleElement()
                .satisfies(row -> assertThat(row.getPayloadJson()).contains("\"validationStatus\":\"PASSED_OOS\""));
    }

    @Test
    void strategyBundleUsesRequestedFactorScoresInsteadOfStaticDefaults() throws Exception {
        mockMvc.perform(get("/api/v2/signals/strategy-bundle")
                        .param("symbol", "000001")
                        .param("companyName", "平安银行")
                        .param("cyclePositionScore", "12")
                        .param("cycleRecoveryScore", "18")
                        .param("rightSideStructureScore", "25")
                        .param("supplyAbsorptionScore", "30")
                        .param("crowdingRiskScore", "80"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longTermSignals[2].strategyCode").value("CYCLE_REVERSAL"))
                .andExpect(jsonPath("$.longTermSignals[2].action").value("WAIT"))
                .andExpect(jsonPath("$.shortRightSideSignal.action").value("WAIT"))
                .andExpect(jsonPath("$.shortRightSideSignal.positionLimit").value(0));
    }

    @Test
    void strategyBundleDefaultsGoldenCrossToNonExecutableAndPreservesReplayEvidence() throws Exception {
        mockMvc.perform(get("/api/v2/signals/strategy-bundle")
                        .param("symbol", "000001")
                        .param("companyName", "平安银行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortRightSideSignal.action").value("WAIT"))
                .andExpect(jsonPath("$.shortRightSideSignal.context.goldenCrossState").value("NONE"))
                .andExpect(jsonPath("$.shortRightSideSignal.context.goldenCrossTradingDays").value("UNKNOWN"))
                .andExpect(jsonPath("$.shortRightSideSignal.context.goldenCrossPriorityTier").value("0"))
                .andExpect(jsonPath("$.shortRightSideSignal.context.goldenCrossRuleVersion")
                        .value("short-golden-cross-v1.0.0"))
                .andExpect(jsonPath("$.shortRightSideSignal.replayPayload.goldenCross.tradingDays").value(-1))
                .andExpect(jsonPath("$.shortRightSideSignal.replayPayload.goldenCross.ruleVersion")
                        .value("short-golden-cross-v1.0.0"));
    }

    @Test
    void strategyBundleRejectsClientSpoofedDecisionWindow() throws Exception {
        when(tradingClockService.shortTermDecisionCheckpoint()).thenReturn("NOT_CONFIRMED:MORNING_CONTINUOUS");

        mockMvc.perform(get("/api/v2/signals/strategy-bundle")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("tradingCheckpoint", "POST_CLOSE_1520")
                        .param("goldenCrossState", "CONFIRMED")
                        .param("goldenCrossTradingDays", "1")
                        .param("goldenCrossPriorityTier", "3")
                        .param("recommendationToken", verifiedShortTermToken(
                                "RIGHT_EARLY_ADD", "ADD", "CONFIRMED", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortRightSideSignal.action").value("WAIT"))
                .andExpect(jsonPath("$.shortRightSideSignal.positionLimit").value(0))
                .andExpect(jsonPath("$.shortRightSideSignal.context.tradingCheckpoint")
                        .value("NOT_CONFIRMED:MORNING_CONTINUOUS"));
    }

    @Test
    void strategyBundleCannotOverrideLegacyWaitWithStrongV2Scores() throws Exception {
        mockMvc.perform(get("/api/v2/signals/strategy-bundle")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("goldenCrossState", "CONFIRMED")
                        .param("goldenCrossTradingDays", "1")
                        .param("goldenCrossPriorityTier", "3")
                        .param("recommendationToken", verifiedShortTermToken(
                                "WATCH_RIGHT_SIDE", "WAIT", "CONFIRMED", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortRightSideSignal.action").value("WAIT"))
                .andExpect(jsonPath("$.shortRightSideSignal.positionLimit").value(0))
                .andExpect(jsonPath("$.shortRightSideSignal.context.legacyCandidateAction")
                        .value("WATCH_RIGHT_SIDE"));
    }

    private String verifiedShortTermToken(
            String candidateAction,
            String adviceAction,
            String tailStatus,
            boolean evidenceAllowsBuy
    ) {
        return attestationService.register(
                RecommendationSource.SHORT_TERM,
                "002714",
                "牧原股份",
                adviceAction,
                new BigDecimal("80"),
                new BigDecimal("40"),
                Instant.now(),
                Map.of(
                        "action", candidateAction,
                        "todayAdvice", Map.of("action", adviceAction),
                        "tailSignal", Map.of("status", tailStatus),
                        "evidenceCompleteness", Map.of("allowsBuy", evidenceAllowsBuy)
                )
        );
    }
}
