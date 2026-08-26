package com.aistock.research.policy;

import java.time.Instant;
import java.util.List;

public record PolicyAiReview(
        String themeCode,
        String themeName,
        String provider,
        String model,
        Integer relevanceScore,
        String direction,
        String directionLabel,
        String phase,
        String phaseLabel,
        String logicSummary,
        List<String> confirmations,
        List<String> disagreements,
        List<String> risks,
        List<String> mustVerify,
        Integer confidence,
        Instant reviewedAt,
        List<String> dataGaps
) {
    public PolicyAiReview {
        confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
        disagreements = disagreements == null ? List.of() : List.copyOf(disagreements);
        risks = risks == null ? List.of() : List.copyOf(risks);
        mustVerify = mustVerify == null ? List.of() : List.copyOf(mustVerify);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static PolicyAiReview unconfigured(String themeCode, String themeName) {
        return unavailable(
                themeCode,
                themeName,
                "未配置大模型 API Key，AI 复核不可用；可在系统配置中设置后重试。"
        );
    }

    public static PolicyAiReview unavailable(String themeCode, String themeName, String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "AI 复核暂不可用。"
                : reason;
        return new PolicyAiReview(
                themeCode,
                themeName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(safeReason)
        );
    }

    public static String directionLabel(String direction) {
        if ("POSITIVE".equals(direction)) {
            return "政策利好";
        }
        if ("NEGATIVE".equals(direction)) {
            return "政策利空";
        }
        if ("NEUTRAL".equals(direction)) {
            return "方向中性";
        }
        return "方向待定";
    }

    public static String phaseLabel(String phase) {
        if ("EARLY".equals(phase)) {
            return "发酵初期";
        }
        if ("ONGOING".equals(phase)) {
            return "持续发酵";
        }
        if ("FADING".equals(phase)) {
            return "边际退潮";
        }
        return "阶段待定";
    }
}
