package com.aistock.research.shortterm;

import java.util.List;

public record ShortTermSignalProfile(
        String primaryFamily,
        String primaryLabel,
        List<String> activeFamilies,
        List<String> evidence,
        List<String> dataGaps
) {
    public ShortTermSignalProfile {
        activeFamilies = activeFamilies == null ? List.of() : List.copyOf(activeFamilies);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermSignalProfile unavailable(String reason) {
        return new ShortTermSignalProfile(
                "UNAVAILABLE", "信号族待补", List.of(), List.of(),
                reason == null || reason.isBlank() ? List.of() : List.of(reason)
        );
    }
}
