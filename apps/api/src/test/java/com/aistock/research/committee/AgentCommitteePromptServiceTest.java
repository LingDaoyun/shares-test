package com.aistock.research.committee;

import com.aistock.research.tradefeedback.StrategyFeedbackSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCommitteePromptServiceTest {

    private final AgentCommitteePromptService service = new AgentCommitteePromptService(new ObjectMapper());

    @Test
    void preservesOneArgumentPreviewCompatibilityWithoutFeedback() {
        AgentCommitteePromptPreview preview = service.preview(report());

        assertThat(preview.historicalFeedback()).isEmpty();
        assertThat(preview.userPrompt()).contains("确定性共识分：72.50");
    }

    @Test
    void labelsHistoricalFeedbackAsGlobalEvidenceAndAddsGuardInstructions() {
        AgentConsensusReport report = report();

        AgentCommitteePromptPreview preview = service.preview(report, List.of(feedback()));

        assertThat(preview.historicalFeedback()).containsExactly(feedback());
        assertThat(preview.userPrompt()).contains(
                "GLOBAL 策略队列证据",
                "并非按当前 symbol 匹配",
                "MISPRICING",
                "mispricing-v2",
                "历史策略反馈只是带样本量的校准证据，不能覆盖公告、财务、流动性和风险否决。",
                "样本不足 20 时不得建议调整分数；达到 20 时，可靠性修正也只能使用输入中的 ±5 上限。",
                "不得修改确定性共识分或 Nacos 配置",
                "committee_summary、counter_evidence 和 consensus_adjustment.stage"
        );
        assertThat(report.consensusScore()).isEqualByComparingTo("72.50");
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
                List.of(opinion()),
                List.of("需求稳定"),
                List.of("估值待复核"),
                List.of("最新公告"),
                Instant.parse("2026-07-12T00:00:00Z")
        );
    }

    private AgentOpinion opinion() {
        return new AgentOpinion(
                "POLICY_STRATEGIST",
                "政策策略 Agent",
                "政策",
                "SUPPORT",
                "支持",
                new BigDecimal("0.80"),
                new BigDecimal("75.00"),
                List.of("政策支持"),
                List.of("兑现待验证"),
                List.of("核查公告")
        );
    }

    private StrategyFeedbackSummary feedback() {
        return new StrategyFeedbackSummary(
                "MISPRICING",
                "mispricing-v2",
                "T20",
                20,
                15,
                new BigDecimal("0.7500"),
                new BigDecimal("4.0000"),
                new BigDecimal("3.0000"),
                new BigDecimal("8.0000"),
                new BigDecimal("-4.0000"),
                new BigDecimal("1.0000"),
                12,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                true,
                true,
                new BigDecimal("3.50")
        );
    }
}
