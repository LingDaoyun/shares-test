package com.aistock.research.quality;

import com.aistock.research.valuation.PeerValuationReport;

import java.math.BigDecimal;
import java.util.List;

public record PeerValuationBrief(
        boolean available,
        String scopeLabel,
        int peerCount,
        BigDecimal currentPe,
        BigDecimal currentPb,
        BigDecimal medianPe,
        BigDecimal medianPb,
        BigDecimal pePeerPercentile,
        BigDecimal pbPeerPercentile,
        List<PeerValuationBriefPeer> peers,
        List<String> conclusions,
        List<String> dataGaps
) {

    public static PeerValuationBrief from(PeerValuationReport report) {
        if (report == null || report.peerCount() <= 0) {
            return unavailable("同业估值样本缺失");
        }
        return new PeerValuationBrief(
                report.peerCount() >= 3,
                report.scopeLabel(),
                report.peerCount(),
                report.currentPe(),
                report.currentPb(),
                report.medianPe(),
                report.medianPb(),
                report.pePeerPercentile(),
                report.pbPeerPercentile(),
                report.peers().stream()
                        .map(peer -> new PeerValuationBriefPeer(
                                peer.symbol(),
                                peer.companyName(),
                                peer.relationType(),
                                peer.peTtm(),
                                peer.pbRatio(),
                                peer.latestPrice()
                        ))
                        .toList(),
                report.conclusions() == null ? List.of() : report.conclusions(),
                report.dataGaps() == null ? List.of() : report.dataGaps()
        );
    }

    public static PeerValuationBrief unavailable(String reason) {
        return new PeerValuationBrief(
                false,
                "同业估值缺口",
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                reason == null || reason.isBlank() ? List.of("同业估值样本缺失") : List.of(reason)
        );
    }
}
