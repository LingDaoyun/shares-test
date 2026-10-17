package com.aistock.research.shortterm;

import java.time.Instant;
import java.util.List;

public record ShortTermCatchUpFollowUpSnapshot(
        Instant fetchedAt, List<ShortTermCatchUpFollowUpResult> results, List<String> dataGaps
) {
    public ShortTermCatchUpFollowUpSnapshot {
        results = results == null ? List.of() : List.copyOf(results);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
