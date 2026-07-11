package com.aistock.research.quality;

import java.util.ArrayList;
import java.util.List;

public record RecommendationEvidenceBundle(
        String symbol,
        PeerValuationBrief peerValuation,
        AgentConsensusBrief agentConsensus,
        List<String> dataGaps
) {

    public RecommendationEvidenceBundle {
        peerValuation = peerValuation == null ? PeerValuationBrief.unavailable("同业估值样本缺失") : peerValuation;
        agentConsensus = agentConsensus == null ? AgentConsensusBrief.unavailable("多 Agent 共识缺失") : agentConsensus;
        dataGaps = normalizeGaps(peerValuation, agentConsensus, dataGaps);
    }

    public static RecommendationEvidenceBundle unavailable(String symbol) {
        return unavailable(symbol, null);
    }

    public static RecommendationEvidenceBundle unavailable(String symbol, String reason) {
        List<String> gaps = reason == null || reason.isBlank() ? List.of() : List.of(reason);
        return new RecommendationEvidenceBundle(
                symbol,
                PeerValuationBrief.unavailable(reason == null || reason.isBlank() ? "行业估值对比暂不可用" : reason),
                AgentConsensusBrief.unavailable(reason == null || reason.isBlank() ? "多 Agent 共识暂不可用" : reason),
                gaps
        );
    }

    public boolean available() {
        return peerValuation.available() || agentConsensus.available();
    }

    public boolean hasExecutableConsensus() {
        return agentConsensus.available()
                && agentConsensus.vetoCount() == 0
                && agentConsensus.reviewCount() <= 1;
    }

    public boolean hasIndustryComparison() {
        return peerValuation.available() && peerValuation.peerCount() >= 3;
    }

    private static List<String> normalizeGaps(
            PeerValuationBrief peerValuation,
            AgentConsensusBrief agentConsensus,
            List<String> explicitGaps
    ) {
        List<String> gaps = new ArrayList<>();
        if (explicitGaps != null) {
            gaps.addAll(explicitGaps);
        }
        if (peerValuation != null && peerValuation.dataGaps() != null) {
            gaps.addAll(peerValuation.dataGaps());
        }
        if (agentConsensus != null && agentConsensus.dataGaps() != null) {
            gaps.addAll(agentConsensus.dataGaps());
        }
        return gaps.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }
}
