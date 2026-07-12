package com.aistock.research.committee;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.aistock.research.evidence.AgentEvidenceCheck;
import com.aistock.research.tradefeedback.StrategyFeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommitteeAiServiceTest {

    private final LlmChatClient llmChatClient = mock(LlmChatClient.class);
    private final StrategyFeedbackService feedbackService = mock(StrategyFeedbackService.class);
    private final AgentCommitteePromptService promptService = new AgentCommitteePromptService(new ObjectMapper());
    private AgentCommitteeAiService service;

    @BeforeEach
    void setUp() {
        service = new AgentCommitteeAiService(llmChatClient, promptService, feedbackService);
        when(feedbackService.promptContext("002714")).thenReturn(List.of());
    }

    @Test
    void previewLooksUpFeedbackExactlyOnce() {
        AgentCommitteePromptPreview preview = service.preview(report());

        assertThat(preview.historicalFeedback()).isEmpty();
        verify(feedbackService, times(1)).promptContext("002714");
    }

    @Test
    void enhanceLooksUpFeedbackOnceAndKeepsEveryDeterministicInvariantUnchanged() throws Exception {
        when(llmChatClient.currentConfig()).thenReturn(new LlmConfigPreview(
                "test", "model", "http://localhost", "json_schema", true, true,
                "test", null, 1000, null));
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new ObjectMapper().readTree("""
                        {
                          "committee_summary":"历史样本支持继续复核",
                          "agent_arguments":[{
                            "agent_code":"POLICY_STRATEGIST",
                            "argument":"正向证据",
                            "counter_evidence":"反向证据",
                            "confidence_note":"样本有限"
                          }],
                          "consensus_adjustment":{
                            "stage":"WAIT_FOR_PRICE",
                            "reason":"历史样本仅作校准",
                            "must_verify":[]
                          }
                        }
                        """));
        AgentConsensusReport deterministic = report();

        AgentConsensusReport enhanced = service.enhance(deterministic);

        assertThat(enhanced.symbol()).isEqualTo(deterministic.symbol());
        assertThat(enhanced.companyName()).isEqualTo(deterministic.companyName());
        assertThat(enhanced.consensusStage()).isEqualTo(deterministic.consensusStage());
        assertThat(enhanced.consensusLabel()).isEqualTo(deterministic.consensusLabel());
        assertThat(enhanced.consensusScore()).isEqualByComparingTo(deterministic.consensusScore());
        assertThat(enhanced.consensusReason()).isEqualTo(deterministic.consensusReason());
        assertThat(enhanced.supportCount()).isEqualTo(deterministic.supportCount());
        assertThat(enhanced.watchCount()).isEqualTo(deterministic.watchCount());
        assertThat(enhanced.reviewCount()).isEqualTo(deterministic.reviewCount());
        assertThat(enhanced.vetoCount()).isEqualTo(deterministic.vetoCount());
        assertThat(enhanced.agreements()).isEqualTo(deterministic.agreements());
        assertThat(enhanced.disagreements()).isEqualTo(deterministic.disagreements());
        assertThat(enhanced.requiredEvidence()).isEqualTo(deterministic.requiredEvidence());
        assertThat(enhanced.generatedAt()).isEqualTo(deterministic.generatedAt());
        assertThat(enhanced.opinions()).hasSameSizeAs(deterministic.opinions());
        for (int index = 0; index < deterministic.opinions().size(); index++) {
            AgentOpinion before = deterministic.opinions().get(index);
            AgentOpinion after = enhanced.opinions().get(index);
            assertThat(after.agentCode()).isEqualTo(before.agentCode());
            assertThat(after.agentName()).isEqualTo(before.agentName());
            assertThat(after.perspective()).isEqualTo(before.perspective());
            assertThat(after.vote()).isEqualTo(before.vote());
            assertThat(after.voteLabel()).isEqualTo(before.voteLabel());
            assertThat(after.score()).isEqualByComparingTo(before.score());
            assertThat(after.confidence()).isEqualByComparingTo(before.confidence());
            assertThat(after.supports()).isEqualTo(before.supports());
            assertThat(after.objections()).isEqualTo(before.objections());
            assertThat(after.requiredEvidence()).isEqualTo(before.requiredEvidence());
            assertThat(after.evidenceChecks()).isEqualTo(before.evidenceChecks());
        }
        assertThat(enhanced.aiSummary()).isEqualTo("历史样本支持继续复核");
        assertThat(enhanced.aiSuggestedStage()).isEqualTo("WAIT_FOR_PRICE");
        assertThat(enhanced.opinions().get(0).aiArgument()).isEqualTo("正向证据");
        assertThat(enhanced.opinions().get(0).aiCounterEvidence()).isEqualTo("反向证据");
        assertThat(enhanced.opinions().get(0).aiConfidenceNote()).isEqualTo("样本有限");
        verify(feedbackService, times(1)).promptContext("002714");
    }

    private AgentConsensusReport report() {
        return new AgentConsensusReport(
                "002714",
                "牧原股份",
                "EVIDENCE_REVIEW",
                "证据复核",
                new BigDecimal("72.50"),
                "确定性规则输出",
                1,
                0,
                0,
                0,
                List.of(new AgentOpinion(
                        "POLICY_STRATEGIST",
                        "政策策略 Agent",
                        "政策",
                        "SUPPORT",
                        "支持",
                        new BigDecimal("0.80"),
                        new BigDecimal("75.00"),
                        List.of("政策支持"),
                        List.of("兑现待验证"),
                        List.of("核查公告"),
                        List.of(new AgentEvidenceCheck(
                                "核查公告",
                                "FOUND",
                                "已找到",
                                "exchange",
                                "公告证据",
                                "https://example.test/disclosure",
                                88
                        )),
                        null,
                        null,
                        null
                )),
                List.of("需求稳定"),
                List.of("估值待复核"),
                List.of("最新公告"),
                Instant.parse("2026-07-12T00:00:00Z")
        );
    }
}
