package com.aistock.research.committee;

import java.util.Map;

public record AgentCommitteePromptPreview(
        String symbol,
        String companyName,
        String modelInstruction,
        String userPrompt,
        Map<String, Object> outputSchema
) {
}
