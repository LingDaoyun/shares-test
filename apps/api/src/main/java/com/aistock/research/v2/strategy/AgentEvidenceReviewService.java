package com.aistock.research.v2.strategy;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentEvidenceReviewService {

    private static final Duration STALE_AFTER = Duration.ofDays(90);

    public AgentEvidenceReview review(List<AgentEvidenceFinding> findings, Instant reviewedAt) {
        Instant now = reviewedAt == null ? Instant.now() : reviewedAt;
        List<String> warnings = new ArrayList<>();
        List<AgentEvidenceFinding> normalized = findings == null
                ? List.of()
                : findings.stream()
                .map(finding -> normalize(finding, now, warnings))
                .toList();

        int supportCount = (int) normalized.stream().filter(item -> item.vote() == AgentEvidenceVote.SUPPORT).count();
        int opposeCount = (int) normalized.stream().filter(item -> item.vote() == AgentEvidenceVote.OPPOSE).count();
        int abstainCount = (int) normalized.stream().filter(item -> item.vote() == AgentEvidenceVote.ABSTAIN).count();
        int sourceOverlapCount = sourceOverlapCount(normalized);
        boolean hasConflict = supportCount > 0 && opposeCount > 0;
        if (sourceOverlapCount > 0) {
            warnings.add("存在 " + sourceOverlapCount + " 个来源重合，Agent 证据独立性不足。");
        }
        if (hasConflict) {
            warnings.add("同时存在支持和反对证据，需要在详情中展示冲突。");
        }
        return new AgentEvidenceReview(
                normalized,
                supportCount,
                opposeCount,
                abstainCount,
                sourceOverlapCount,
                hasConflict,
                warnings.stream().distinct().toList());
    }

    private AgentEvidenceFinding normalize(
            AgentEvidenceFinding finding,
            Instant reviewedAt,
            List<String> warnings
    ) {
        if (finding == null) {
            warnings.add("空 Agent 证据已强制弃权。");
            return abstain("UNKNOWN", "UNKNOWN", "空 Agent 证据。");
        }
        if (!hasEvidence(finding)) {
            warnings.add(text(finding.agentName(), "UNKNOWN") + " 缺少可核验证据，已强制弃权。");
            return abstain(finding.agentName(), finding.role(), finding.claim());
        }
        if (finding.publishedAt() != null && finding.publishedAt().plus(STALE_AFTER).isBefore(reviewedAt)) {
            warnings.add(text(finding.agentName(), "UNKNOWN") + " 证据超过 90 天，已强制弃权。");
            return abstain(finding.agentName(), finding.role(), finding.claim());
        }
        return new AgentEvidenceFinding(
                text(finding.agentName(), "UNKNOWN"),
                text(finding.role(), "UNKNOWN"),
                finding.vote() == null ? AgentEvidenceVote.ABSTAIN : finding.vote(),
                finding.sourceUrl(),
                finding.sourceTitle(),
                finding.publishedAt(),
                finding.evidenceHash(),
                finding.claim());
    }

    private AgentEvidenceFinding abstain(String agentName, String role, String claim) {
        return new AgentEvidenceFinding(
                text(agentName, "UNKNOWN"),
                text(role, "UNKNOWN"),
                AgentEvidenceVote.ABSTAIN,
                "",
                "",
                null,
                "",
                text(claim, "缺少证据。"));
    }

    private boolean hasEvidence(AgentEvidenceFinding finding) {
        return !blank(finding.sourceUrl())
                && !blank(finding.sourceTitle())
                && !blank(finding.evidenceHash())
                && finding.publishedAt() != null;
    }

    private int sourceOverlapCount(List<AgentEvidenceFinding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        findings.stream()
                .filter(finding -> finding.vote() != AgentEvidenceVote.ABSTAIN)
                .filter(finding -> !blank(finding.sourceUrl()))
                .forEach(finding -> counts.merge(finding.sourceUrl(), 1, Integer::sum));
        return (int) counts.values().stream().filter(count -> count > 1).count();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String text(String value, String fallback) {
        return blank(value) ? fallback : value;
    }
}
