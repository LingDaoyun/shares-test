package com.aistock.research.firstboard;

public record FirstBoardAlertCoverage(
        int universeCount,
        int supportedCount,
        int baselineCount,
        int scopedBaselineCount,
        int quotedCount,
        int eligibleCount,
        int excludedNameCount,
        int insufficientHistoryCount,
        int excludedMissingQuoteCount,
        int excludedTouchedLimitCount,
        int excludedVolumeRatioCount
) {
}
