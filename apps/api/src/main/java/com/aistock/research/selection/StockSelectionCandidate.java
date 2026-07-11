package com.aistock.research.selection;

import com.aistock.research.committee.AgentConsensusReport;

import java.math.BigDecimal;
import java.util.List;

public record StockSelectionCandidate(
        int rank,
        String symbol,
        String companyName,
        String market,
        String industry,
        BigDecimal finalScore,
        String selectionLabel,
        String selectionReason,
        AgentConsensusReport discussion,
        List<StockSelectionTraceStep> trace
) {
}
