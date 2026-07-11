package com.aistock.research.quality;

import java.math.BigDecimal;

public record PeerValuationBriefPeer(
        String symbol,
        String companyName,
        String relationType,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal latestPrice
) {
}
