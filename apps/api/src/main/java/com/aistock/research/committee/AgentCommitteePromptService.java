package com.aistock.research.committee;

import com.aistock.research.tradefeedback.StrategyFeedbackSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentCommitteePromptService {

    private final ObjectMapper objectMapper;

    public AgentCommitteePromptService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentCommitteePromptPreview preview(AgentConsensusReport report) {
        return preview(report, List.of());
    }

    public AgentCommitteePromptPreview preview(
            AgentConsensusReport report,
            List<StrategyFeedbackSummary> historicalFeedback
    ) {
        List<StrategyFeedbackSummary> feedback = historicalFeedback == null
                ? List.of()
                : List.copyOf(historicalFeedback);
        return new AgentCommitteePromptPreview(
                report.symbol(),
                report.companyName(),
                modelInstruction(),
                userPrompt(report, feedback),
                feedback,
                outputSchema()
        );
    }

    public String modelInstruction() {
        return """
                你是一个面向中国 A 股长线投资研究的多 Agent 投研委员会主持人。
                你的任务不是给出买卖指令，而是基于输入证据组织五个 Agent 的交叉讨论，指出共识、分歧、反证和下一步必须补充的证据。
                严格约束：
                - 只能使用输入中的结构化证据，不得编造公告、财务数据、新闻或股价。
                - 如果证据不足，必须明确写入 counter_evidence 或 must_verify。
                - 不允许输出“立即买入”“保证收益”等投资建议。
                - 最终只输出一个合法 JSON 对象，不要输出 Markdown、代码块或解释文字。
                """;
    }

    public String userPrompt(AgentConsensusReport report) {
        return userPrompt(report, List.of());
    }

    public String userPrompt(
            AgentConsensusReport report,
            List<StrategyFeedbackSummary> historicalFeedback
    ) {
        return """
                请围绕下面这家公司组织一次多 Agent 辩论，并输出 json 对象。

                公司：%s(%s)
                确定性共识阶段：%s / %s
                确定性共识分：%s
                确定性共识理由：%s

                投票统计：
                - 支持：%s
                - 观察：%s
                - 复核：%s
                - 否决：%s

                Agent 结构化意见：
                %s

                已形成共识：
                %s

                主要分歧：
                %s

                待补证据：
                %s

                历史策略反馈（GLOBAL 策略队列证据，并非按当前 symbol 匹配）：
                %s

                历史反馈使用约束：
                - 历史策略反馈只是带样本量的校准证据，不能覆盖公告、财务、流动性和风险否决。
                - 样本不足 20 时不得建议调整分数；达到 20 时，可靠性修正也只能使用输入中的 ±5 上限。
                - 为控制提示词长度，最多注入 12 个符合条件的 GLOBAL 队列，按样本量和稳定标识排序。
                - 此证据只可用于 committee_summary、counter_evidence 和 consensus_adjustment.stage。
                - 不得修改确定性共识分或 Nacos 配置，也不得将 GLOBAL 队列证据描述为当前公司的策略命中记录。

                输出格式要求：
                - 必须是合法 JSON 对象。
                - 顶层必须包含 committee_summary、agent_arguments、consensus_adjustment。
                - agent_arguments 必须包含输入中的五个 agent_code。
                - 不要改变确定性共识分；如果认为阶段应调整，只能写在 consensus_adjustment.stage。

                JSON schema 示例：
                %s
                """.formatted(
                report.companyName(),
                report.symbol(),
                report.consensusStage(),
                report.consensusLabel(),
                report.consensusScore(),
                report.consensusReason(),
                report.supportCount(),
                report.watchCount(),
                report.reviewCount(),
                report.vetoCount(),
                serialize(opinionPayload(report.opinions())),
                serialize(report.agreements()),
                serialize(report.disagreements()),
                serialize(report.requiredEvidence()),
                serialize(feedbackPayload(historicalFeedback)),
                serialize(outputExample())
        );
    }

    public Map<String, Object> outputSchema() {
        Map<String, Object> agentArgument = new LinkedHashMap<>();
        agentArgument.put("type", "object");
        agentArgument.put("additionalProperties", false);
        agentArgument.put("required", List.of("agent_code", "argument", "counter_evidence", "confidence_note"));
        agentArgument.put("properties", Map.of(
                "agent_code", Map.of("type", "string"),
                "argument", Map.of("type", "string"),
                "counter_evidence", Map.of("type", "string"),
                "confidence_note", Map.of("type", "string")
        ));

        Map<String, Object> adjustment = new LinkedHashMap<>();
        adjustment.put("type", "object");
        adjustment.put("additionalProperties", false);
        adjustment.put("required", List.of("stage", "reason", "must_verify"));
        adjustment.put("properties", Map.of(
                "stage", Map.of("type", "string"),
                "reason", Map.of("type", "string"),
                "must_verify", Map.of("type", "array", "items", Map.of("type", "string"))
        ));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("required", List.of("committee_summary", "agent_arguments", "consensus_adjustment"));
        root.put("properties", Map.of(
                "committee_summary", Map.of("type", "string"),
                "agent_arguments", Map.of("type", "array", "items", agentArgument),
                "consensus_adjustment", adjustment
        ));
        return root;
    }

    private List<Map<String, Object>> opinionPayload(List<AgentOpinion> opinions) {
        return opinions.stream()
                .map(opinion -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("agent_code", opinion.agentCode());
                    payload.put("agent_name", opinion.agentName());
                    payload.put("perspective", opinion.perspective());
                    payload.put("vote", opinion.vote());
                    payload.put("vote_label", opinion.voteLabel());
                    payload.put("score", opinion.score());
                    payload.put("confidence", opinion.confidence());
                    payload.put("supports", opinion.supports());
                    payload.put("objections", opinion.objections());
                    payload.put("required_evidence", opinion.requiredEvidence());
                    payload.put("evidence_checks", opinion.evidenceChecks());
                    return payload;
                })
                .toList();
    }

    private Map<String, Object> outputExample() {
        return Map.of(
                "committee_summary", "基于已给证据的简短委员会结论。",
                "agent_arguments", List.of(Map.of(
                        "agent_code", "POLICY_STRATEGIST",
                        "argument", "该 Agent 的正向论证。",
                        "counter_evidence", "该 Agent 认为仍需核查的反证或证据缺口。",
                        "confidence_note", "对信心来源和不足的说明。"
                )),
                "consensus_adjustment", Map.of(
                        "stage", "EVIDENCE_REVIEW",
                        "reason", "如需调整阶段，在这里说明理由；否则沿用确定性阶段。",
                        "must_verify", List.of("必须补充的证据项")
                )
        );
    }

    private List<Map<String, Object>> feedbackPayload(List<StrategyFeedbackSummary> feedback) {
        return feedback.stream().map(summary -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceModule", summary.sourceModule());
            payload.put("ruleVersion", summary.ruleVersion());
            payload.put("horizon", summary.horizon());
            payload.put("sampleCount", summary.sampleCount());
            payload.put("positiveCount", summary.positiveCount());
            payload.put("positiveRate", summary.positiveRate());
            payload.put("averageReturn", summary.averageReturn());
            payload.put("medianReturn", summary.medianReturn());
            payload.put("averageRunup", summary.averageRunup());
            payload.put("averageDrawdown", summary.averageDrawdown());
            payload.put("averageExecutionDeviation", summary.averageExecutionDeviation());
            payload.put("executionDeviationSampleCount", summary.executionDeviationSampleCount());
            payload.put("sampleStart", isoDate(summary.sampleStart()));
            payload.put("sampleEnd", isoDate(summary.sampleEnd()));
            payload.put("promptEligible", summary.promptEligible());
            payload.put("adjustmentEligible", summary.adjustmentEligible());
            payload.put("reliabilityAdjustment", summary.reliabilityAdjustment());
            return payload;
        }).toList();
    }

    private String isoDate(LocalDate date) {
        return date == null ? null : DateTimeFormatter.ISO_LOCAL_DATE.format(date);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize committee prompt payload as JSON", exception);
        }
    }
}
