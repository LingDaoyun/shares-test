package com.aistock.research.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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
        return new AgentCommitteePromptPreview(
                report.symbol(),
                report.companyName(),
                modelInstruction(),
                userPrompt(report),
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

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
