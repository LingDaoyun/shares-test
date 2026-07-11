package com.aistock.research.trading;

import java.util.List;

public record TradingSessionSnapshot(
        String phase,
        String phaseLabel,
        boolean regularAuctionOpen,
        boolean closingDecisionWindow,
        boolean postCloseFixedPrice,
        String decisionTimeLabel,
        List<String> rules,
        List<String> warnings
) {
}
