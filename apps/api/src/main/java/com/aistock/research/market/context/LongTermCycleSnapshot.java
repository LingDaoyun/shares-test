package com.aistock.research.market.context;

import java.util.List;

public record LongTermCycleSnapshot(
        String businessStage,
        String businessStageLabel,
        String priceStage,
        String priceStageLabel,
        int confidence,
        boolean provisional,
        List<String> supportingEvidence,
        List<String> contraryEvidence,
        List<String> dataGaps
) {
}
