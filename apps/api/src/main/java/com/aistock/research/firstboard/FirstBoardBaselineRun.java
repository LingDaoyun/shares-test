package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FirstBoardBaselineRun(
        UUID runId,
        LocalDate scanTradeDate,
        LocalDate baselineTradeDate,
        String ruleVersion,
        String barDataVersion,
        FirstBoardBaselineRunStatus status,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount,
        int itemCount,
        Instant startedAt,
        Instant publishedAt,
        String errorSummary
) {
}
