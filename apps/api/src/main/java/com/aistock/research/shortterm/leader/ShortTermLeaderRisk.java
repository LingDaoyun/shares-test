package com.aistock.research.shortterm.leader;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShortTermLeaderRisk(
        String ruleVersion,
        Status status,
        BaselineType baselineType,
        Instant baselineAt,
        List<ShortTermLeaderRiskSignal> signals,
        String dominantCandidateIndustry,
        BigDecimal candidateConcentrationPercent,
        boolean directionConflict,
        String summary,
        String evidence,
        List<String> dataGaps,
        boolean advisoryOnly,
        Instant evaluatedAt
) {

    private static final String CURRENT_RULE_VERSION = "short-term-leader-risk-v1-sensitive";

    public ShortTermLeaderRisk {
        ruleVersion = text(ruleVersion);
        status = status == null ? Status.UNAVAILABLE : status;
        baselineType = baselineType == null ? BaselineType.INITIAL : baselineType;
        signals = signals == null ? List.of() : List.copyOf(signals);
        dominantCandidateIndustry = nullableText(dominantCandidateIndustry);
        candidateConcentrationPercent = candidateConcentrationPercent == null
                ? BigDecimal.ZERO
                : candidateConcentrationPercent;
        summary = text(summary);
        evidence = text(evidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        advisoryOnly = true;
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }

    public static ShortTermLeaderRisk unavailable(String reason) {
        String safeReason = text(reason);
        return new ShortTermLeaderRisk(
                CURRENT_RULE_VERSION,
                Status.UNAVAILABLE,
                BaselineType.INITIAL,
                null,
                List.of(),
                null,
                BigDecimal.ZERO,
                false,
                safeReason,
                "龙头异动风险未执行。",
                safeReason.isBlank() ? List.of() : List.of(safeReason),
                true,
                Instant.now()
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullableText(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? null : normalized;
    }

    public enum Status { WARNING, CLEAR, BASELINE_BUILDING, UNAVAILABLE }

    public enum BaselineType { PREVIOUS_SCAN, PREVIOUS_TRADING_DAY, INITIAL }
}
