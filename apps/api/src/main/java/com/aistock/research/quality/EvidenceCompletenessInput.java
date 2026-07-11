package com.aistock.research.quality;

import java.util.List;

public record EvidenceCompletenessInput(
        boolean shortTerm,
        boolean realtimeQuote,
        boolean valuation,
        boolean kline,
        boolean financial,
        boolean filing,
        boolean industryComparison,
        boolean fundFlow,
        boolean intraday,
        List<String> explicitGaps
) {

    public static EvidenceCompletenessInput longTerm(
            boolean realtimeQuote,
            boolean valuation,
            boolean kline,
            boolean financial,
            boolean filing,
            boolean industryComparison,
            boolean fundFlow,
            List<String> explicitGaps
    ) {
        return new EvidenceCompletenessInput(
                false,
                realtimeQuote,
                valuation,
                kline,
                financial,
                filing,
                industryComparison,
                fundFlow,
                false,
                explicitGaps == null ? List.of() : explicitGaps
        );
    }

    public static EvidenceCompletenessInput shortTerm(
            boolean realtimeQuote,
            boolean valuation,
            boolean kline,
            boolean financial,
            boolean intraday,
            boolean filing,
            boolean fundFlow,
            List<String> explicitGaps
    ) {
        return new EvidenceCompletenessInput(
                true,
                realtimeQuote,
                valuation,
                kline,
                financial,
                filing,
                false,
                fundFlow,
                intraday,
                explicitGaps == null ? List.of() : explicitGaps
        );
    }
}
