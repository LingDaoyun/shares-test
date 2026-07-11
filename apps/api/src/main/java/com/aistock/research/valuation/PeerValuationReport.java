package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.util.List;

public record PeerValuationReport(
        String scope,
        String scopeLabel,
        int peerCount,
        BigDecimal currentPe,
        BigDecimal currentPb,
        BigDecimal medianPe,
        BigDecimal medianPb,
        BigDecimal averagePe,
        BigDecimal averagePb,
        BigDecimal pePeerPercentile,
        BigDecimal pbPeerPercentile,
        int cheaperPeCount,
        int cheaperPbCount,
        List<PeerValuationCompany> peers,
        List<String> conclusions,
        List<String> dataGaps
) {
}
