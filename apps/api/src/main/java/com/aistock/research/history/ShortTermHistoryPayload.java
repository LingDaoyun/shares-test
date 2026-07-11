package com.aistock.research.history;

import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermHotDirection;
import com.aistock.research.shortterm.ShortTermMarketSentiment;
import com.aistock.research.shortterm.ShortTermRuleSet;
import com.aistock.research.trading.TradingSessionSnapshot;

import java.time.Instant;
import java.util.List;

public record ShortTermHistoryPayload(
        ShortTermCandidate candidate,
        ShortTermMarketSentiment marketSentiment,
        List<ShortTermHotDirection> hotDirections,
        ShortTermRuleSet ruleSet,
        TradingSessionSnapshot tradingSession,
        String quoteNote,
        int universeCount,
        int reviewedCount,
        int klineReviewedCount,
        Instant generatedAt
) {
}
