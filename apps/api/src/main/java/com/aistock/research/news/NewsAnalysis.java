package com.aistock.research.news;

import java.time.Instant;
import java.util.List;

/** A null analyzedAt denotes an explicit unsuccessful attempt; only successful analyses are cached. */
public record NewsAnalysis(String itemId, String summary, List<String> affectedSectors,
                           String transmission, List<String> watchPoints, List<String> risks,
                           String evidenceUrl, Instant analyzedAt, List<String> dataGaps) {
    public NewsAnalysis {
        affectedSectors = affectedSectors == null ? List.of() : List.copyOf(affectedSectors);
        watchPoints = watchPoints == null ? List.of() : List.copyOf(watchPoints);
        risks = risks == null ? List.of() : List.copyOf(risks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
