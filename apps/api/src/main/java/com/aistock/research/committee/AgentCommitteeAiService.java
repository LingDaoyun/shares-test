package com.aistock.research.committee;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentCommitteeAiService {

    private final LlmChatClient llmChatClient;
    private final AgentCommitteePromptService promptService;

    public AgentCommitteeAiService(LlmChatClient llmChatClient, AgentCommitteePromptService promptService) {
        this.llmChatClient = llmChatClient;
        this.promptService = promptService;
    }

    public AgentConsensusReport enhance(AgentConsensusReport deterministicReport) {
        LlmConfigPreview config = llmChatClient.currentConfig();
        try {
            AgentCommitteePromptPreview prompt = promptService.preview(deterministicReport);
            JsonNode json = llmChatClient.completeJson(
                    prompt.modelInstruction(),
                    prompt.userPrompt(),
                    "agent_committee_consensus",
                    prompt.outputSchema()
            );
            List<String> warnings = validate(json, deterministicReport);
            return deterministicReport.withAiEnhancement(
                    config.provider(),
                    config.model(),
                    text(json.path("committee_summary")),
                    text(json.path("consensus_adjustment").path("stage")),
                    mergeOpinions(deterministicReport.opinions(), json.path("agent_arguments")),
                    warnings
            );
        } catch (RuntimeException exception) {
            return deterministicReport.withAiWarning(config.provider(), config.model(), exception.getMessage());
        }
    }

    public AgentCommitteePromptPreview preview(AgentConsensusReport deterministicReport) {
        return promptService.preview(deterministicReport);
    }

    private List<AgentOpinion> mergeOpinions(List<AgentOpinion> opinions, JsonNode argumentsNode) {
        Map<String, JsonNode> argumentsByAgent = new LinkedHashMap<>();
        if (argumentsNode != null && argumentsNode.isArray()) {
            for (JsonNode argument : argumentsNode) {
                String agentCode = text(argument.path("agent_code"));
                if (agentCode != null) {
                    argumentsByAgent.put(agentCode, argument);
                }
            }
        }
        return opinions.stream()
                .map(opinion -> {
                    JsonNode argument = argumentsByAgent.get(opinion.agentCode());
                    if (argument == null) {
                        return opinion;
                    }
                    return opinion.withAiArgument(
                            text(argument.path("argument")),
                            text(argument.path("counter_evidence")),
                            text(argument.path("confidence_note"))
                    );
                })
                .toList();
    }

    private List<String> validate(JsonNode json, AgentConsensusReport report) {
        List<String> warnings = new ArrayList<>();
        if (!hasText(text(json.path("committee_summary")))) {
            warnings.add("AI 输出缺少 committee_summary");
        }
        JsonNode arguments = json.path("agent_arguments");
        if (!arguments.isArray()) {
            warnings.add("AI 输出缺少 agent_arguments 数组");
            return warnings;
        }
        for (AgentOpinion opinion : report.opinions()) {
            boolean present = false;
            for (JsonNode argument : arguments) {
                if (opinion.agentCode().equals(text(argument.path("agent_code")))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                warnings.add("AI 输出缺少 " + opinion.agentName() + " 的论证");
            }
        }
        return warnings;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
