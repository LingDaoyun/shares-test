package com.aistock.research.v2.api;

import com.aistock.research.v2.strategy.AgentEvidenceReview;

import java.time.Instant;
import java.util.List;

public record V2StrategyBundleResponse(
        String symbol,
        String companyName,
        Instant generatedAt,
        List<V2SignalResponse> longTermSignals,
        V2SignalResponse shortRightSideSignal,
        AgentEvidenceReview agentEvidenceReview
) {
    public V2StrategyBundleResponse {
        longTermSignals = longTermSignals == null ? List.of() : List.copyOf(longTermSignals);
    }
}
