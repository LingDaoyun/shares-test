package com.aistock.research.quality;

import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.committee.AgentOpinion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record AgentConsensusBrief(
        boolean available,
        String consensusLabel,
        BigDecimal consensusScore,
        int supportCount,
        int watchCount,
        int reviewCount,
        int vetoCount,
        String contrarianSummary,
        List<String> requiredEvidence,
        List<String> objections,
        List<String> dataGaps
) {

    public static AgentConsensusBrief from(AgentConsensusReport report) {
        if (report == null) {
            return unavailable("多 Agent 共识缺失");
        }
        List<String> objections = report.opinions() == null
                ? List.of()
                : report.opinions().stream()
                .filter(opinion -> opinion.objections() != null)
                .flatMap(opinion -> opinion.objections().stream())
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        return new AgentConsensusBrief(
                true,
                report.consensusLabel(),
                report.consensusScore(),
                report.supportCount(),
                report.watchCount(),
                report.reviewCount(),
                report.vetoCount(),
                contrarianSummary(report.opinions(), objections),
                report.requiredEvidence() == null ? List.of() : report.requiredEvidence(),
                objections,
                report.vetoCount() > 0 ? List.of("多 Agent 存在否决票，不能直接进入买入闸门。") : List.of()
        );
    }

    public static AgentConsensusBrief unavailable(String reason) {
        return new AgentConsensusBrief(
                false,
                "Agent 共识缺口",
                null,
                0,
                0,
                0,
                0,
                "多 Agent 共识暂不可用。",
                List.of(),
                List.of(),
                reason == null || reason.isBlank() ? List.of("多 Agent 共识缺失") : List.of(reason)
        );
    }

    private static String contrarianSummary(List<AgentOpinion> opinions, List<String> allObjections) {
        if (opinions != null) {
            List<String> contrarianObjections = opinions.stream()
                    .filter(opinion -> "RISK_CONTRARIAN".equals(opinion.agentCode())
                            || "VETO".equals(opinion.vote())
                            || "REVIEW".equals(opinion.vote()))
                    .filter(opinion -> opinion.objections() != null)
                    .flatMap(opinion -> opinion.objections().stream())
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
            if (!contrarianObjections.isEmpty()) {
                return String.join("；", contrarianObjections);
            }
        }
        if (allObjections != null && !allObjections.isEmpty()) {
            List<String> sample = new ArrayList<>(allObjections.stream().limit(3).toList());
            return String.join("；", sample);
        }
        return "反方暂未发现硬性否决。";
    }
}
