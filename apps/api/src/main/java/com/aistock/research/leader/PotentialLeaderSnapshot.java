package com.aistock.research.leader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 潜力龙头模块的完整对外快照。正式排名只来自确定性规则；校准与 AI 均为影子信息。
 */
public record PotentialLeaderSnapshot(
        Status status,
        LocalDate tradeDate,
        LocalDate quoteTradeDate,
        Instant quoteTimestamp,
        LocalDate dragonTigerDate,
        Instant generatedAt,
        boolean autoRefreshAllowed,
        String ruleVersion,
        BoardOptions boardOptions,
        MarketSummary marketSummary,
        List<Direction> directions,
        List<Candidate> potentialCandidates,
        List<Candidate> competitionCandidates,
        Coverage coverage,
        List<String> dataGaps
) {
    public PotentialLeaderSnapshot {
        directions = immutable(directions);
        potentialCandidates = immutable(potentialCandidates);
        competitionCandidates = immutable(competitionCandidates);
        dataGaps = immutable(dataGaps);
    }

    public enum Status { PREPARING, READY, STALE, UNAVAILABLE }

    public enum Lane { POTENTIAL, COMPETITION }

    public enum Grade { A, B, C }

    public enum DisclosureStatus { DISCLOSED, NOT_DISCLOSED, FETCH_FAILED, STALE_DISCLOSURE }

    public enum CalibrationStatus { READY, COLLECTING }

    public record BoardOptions(boolean includeGrowth, boolean includeStar) {
    }

    public record MarketSummary(
            int limitUpCount,
            Integer brokenCount,
            int maxBoard,
            BigDecimal sealRatePercent,
            BigDecimal boards2PlusRatioPercent,
            String sentimentTone
    ) {
    }

    public record Direction(
            String industry,
            int rank,
            BigDecimal score,
            BigDecimal breadthPercent,
            BigDecimal medianChangePercent,
            int limitUpCount,
            int brokenCount,
            int maxBoard,
            BigDecimal amount,
            List<String> constituentSymbols
    ) {
        public Direction {
            constituentSymbols = immutable(constituentSymbols);
        }
    }

    public record ScoreComponent(
            String code,
            String label,
            BigDecimal rawValue,
            BigDecimal points,
            BigDecimal maximumPoints
    ) {
    }

    public record RiskDeduction(
            String code,
            String label,
            BigDecimal rawValue,
            BigDecimal threshold,
            BigDecimal points
    ) {
    }

    public record Seat(
            String side,
            String branchCode,
            String branchName,
            BigDecimal buyAmount,
            BigDecimal sellAmount,
            BigDecimal netAmount,
            boolean institution,
            boolean stockConnect
    ) {
    }

    public record DragonTigerEvidence(
            DisclosureStatus status,
            LocalDate disclosureDate,
            BigDecimal adjustment,
            List<Seat> seats,
            List<String> dataGaps
    ) {
        public DragonTigerEvidence {
            seats = immutable(seats);
            dataGaps = immutable(dataGaps);
        }
    }

    public record Calibration(
            CalibrationStatus status,
            int sampleSize,
            BigDecimal successRate,
            BigDecimal lower95,
            BigDecimal upper95
    ) {
    }

    public record Candidate(
            Lane lane,
            int rank,
            Grade grade,
            BigDecimal score,
            String symbol,
            String name,
            String industry,
            String board,
            BigDecimal latestPrice,
            BigDecimal changePercent,
            BigDecimal realtimeVolumeRatio,
            BigDecimal turnoverRate,
            BigDecimal amount,
            BigDecimal circulatingMarketValue,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal ma20Slope,
            BigDecimal breakoutPositionPercent,
            boolean currentlySealed,
            int consecutiveBoards,
            LocalTime firstSealTime,
            LocalTime lastSealTime,
            int sealBreakCount,
            BigDecimal sealFunds,
            List<ScoreComponent> scoreBreakdown,
            List<RiskDeduction> riskDeductions,
            List<String> evidence,
            DragonTigerEvidence dragonTiger,
            Calibration calibration,
            String snapshotHash
    ) {
        public Candidate {
            scoreBreakdown = immutable(scoreBreakdown);
            riskDeductions = immutable(riskDeductions);
            evidence = immutable(evidence);
        }
    }

    public record Coverage(
            int universeCount,
            int baselineCount,
            int scopedCount,
            int liquidCount,
            int strongDirectionCount,
            int potentialEligibleCount,
            int competitionEligibleCount,
            int hiddenBelowScoreCount,
            int excludedMissingDataCount
    ) {
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
