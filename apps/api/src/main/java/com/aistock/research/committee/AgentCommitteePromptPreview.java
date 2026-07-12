package com.aistock.research.committee;

import com.aistock.research.tradefeedback.StrategyFeedbackSummary;

import java.util.List;
import java.util.Map;

public record AgentCommitteePromptPreview(
        String symbol,
        String companyName,
        String modelInstruction,
        String userPrompt,
        List<StrategyFeedbackSummary> historicalFeedback,
        Map<String, Object> outputSchema
) {
}
