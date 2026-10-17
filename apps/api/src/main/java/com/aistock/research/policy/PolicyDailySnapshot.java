package com.aistock.research.policy;

import java.time.Instant;
import java.util.List;

/** Public read model. It contains evidence and review coverage, never LLM configuration. */
public record PolicyDailySnapshot(
        String status,
        String scheduledTime,
        Instant startedAt,
        Instant completedAt,
        List<PolicyTheme> themes,
        List<String> reviewedThemeCodes,
        List<String> dataGaps
) {
    public static final String SCHEDULED_TIME = "每天08:30（北京时间）";

    public PolicyDailySnapshot {
        themes = themes == null ? List.of() : List.copyOf(themes);
        reviewedThemeCodes = reviewedThemeCodes == null ? List.of() : List.copyOf(reviewedThemeCodes);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    static PolicyDailySnapshot idle() {
        return new PolicyDailySnapshot("IDLE", SCHEDULED_TIME, null, null, List.of(), List.of(), List.of());
    }
}
