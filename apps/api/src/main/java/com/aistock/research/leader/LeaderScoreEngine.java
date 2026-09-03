package com.aistock.research.leader;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LeaderScoreEngine {

    private static final LocalTime EARLY_SEAL = LocalTime.of(10, 0);
    private static final LocalTime LATE_SEAL = LocalTime.of(14, 30);

    public List<PotentialLeaderSnapshot.Candidate> rankPotential(
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions,
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections
    ) {
        return rankPotential(rows, directions, previousDirections, Map.of());
    }

    List<PotentialLeaderSnapshot.Candidate> rankPotential(
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions,
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections,
            Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> ignoredDragonTiger
    ) {
        return rank(
                PotentialLeaderSnapshot.Lane.POTENTIAL, rows, directions, null,
                Map.of(), previousDirections);
    }

    public List<PotentialLeaderSnapshot.Candidate> rankCompetition(
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions,
            PotentialLeaderSnapshot.MarketSummary marketSummary,
            Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> dragonTiger,
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections
    ) {
        return rank(
                PotentialLeaderSnapshot.Lane.COMPETITION, rows, directions, marketSummary,
                dragonTiger, previousDirections);
    }

    private List<PotentialLeaderSnapshot.Candidate> rank(
            PotentialLeaderSnapshot.Lane lane,
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions,
            PotentialLeaderSnapshot.MarketSummary marketSummary,
            Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> dragonTiger,
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections
    ) {
        List<LeaderCandidateEngine.ScorableCandidate> laneRows = safe(rows).stream()
                .filter(row -> row != null && row.lane() == lane)
                .toList();
        List<Draft> drafts = new ArrayList<>();
        for (LeaderCandidateEngine.ScorableCandidate row : laneRows) {
            PotentialLeaderSnapshot.DragonTigerEvidence evidence = lane == PotentialLeaderSnapshot.Lane.COMPETITION
                    ? safeEvidence(dragonTiger == null ? null : dragonTiger.get(row.quote().symbol()))
                    : noDragonTigerEvidence();
            List<PotentialLeaderSnapshot.ScoreComponent> components = lane == PotentialLeaderSnapshot.Lane.POTENTIAL
                    ? potentialComponents(row)
                    : competitionComponents(row, laneRows, directions, marketSummary);
            BigDecimal base = components.stream()
                    .map(PotentialLeaderSnapshot.ScoreComponent::points)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<PotentialLeaderSnapshot.RiskDeduction> risks = risks(
                    row, evidence, previousDirections == null ? Map.of() : previousDirections);
            BigDecimal risk = capRisk(risks.stream()
                    .map(PotentialLeaderSnapshot.RiskDeduction::points)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal seatAdjustment = lane == PotentialLeaderSnapshot.Lane.COMPETITION
                    ? LeaderRuleSet.clamp(
                            evidence.adjustment(), LeaderRuleSet.MIN_SEAT_ADJUSTMENT,
                            LeaderRuleSet.MAX_SEAT_ADJUSTMENT)
                    : BigDecimal.ZERO;
            BigDecimal official = LeaderRuleSet.scale(LeaderRuleSet.clamp(
                    base.add(seatAdjustment).subtract(risk),
                    LeaderRuleSet.ZERO, LeaderRuleSet.HUNDRED));
            PotentialLeaderSnapshot.Grade grade = grade(official);
            if (grade == null) {
                continue;
            }
            BigDecimal breakoutPosition = breakoutPosition(row);
            List<String> explanation = evidence(row, components, risks, seatAdjustment);
            String hash = snapshotHash(row, official, components, risks, evidence);
            drafts.add(new Draft(new PotentialLeaderSnapshot.Candidate(
                    lane, 0, grade, official,
                    row.quote().symbol(), row.quote().name(), row.direction().industry(),
                    row.baseline().board().name(), row.quote().latestPrice(), row.quote().changePercent(),
                    row.quote().realtimeVolumeRatio(), row.quote().turnoverRate(), row.quote().amount(),
                    row.quote().circulatingMarketValue(), row.baseline().ma10(), row.baseline().ma20(),
                    row.baseline().ma20Slope(), breakoutPosition,
                    row.boardState().currentlySealed(), row.boardState().consecutiveBoards(),
                    row.boardState().firstSealTime(), row.boardState().lastSealTime(),
                    row.boardState().sealBreakCount(), row.boardState().sealFunds(),
                    components, risks, explanation, evidence, null, hash), risk));
        }
        drafts.sort(Comparator
                .comparing((Draft draft) -> draft.candidate().score()).reversed()
                .thenComparing(Draft::risk)
                .thenComparing(draft -> directionRank(draft.candidate().industry(), directions))
                .thenComparing(
                        draft -> draft.candidate().amount(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(draft -> draft.candidate().symbol()));
        List<PotentialLeaderSnapshot.Candidate> ranked = new ArrayList<>();
        for (int index = 0; index < drafts.size() && index < 10; index++) {
            ranked.add(withRank(drafts.get(index).candidate(), index + 1));
        }
        return List.copyOf(ranked);
    }

    PotentialLeaderSnapshot.Grade grade(BigDecimal score) {
        if (score == null || score.compareTo(new BigDecimal("55")) < 0) {
            return null;
        }
        if (score.compareTo(new BigDecimal("80")) >= 0) {
            return PotentialLeaderSnapshot.Grade.A;
        }
        if (score.compareTo(new BigDecimal("65")) >= 0) {
            return PotentialLeaderSnapshot.Grade.B;
        }
        return PotentialLeaderSnapshot.Grade.C;
    }

    BigDecimal capRisk(BigDecimal risk) {
        return LeaderRuleSet.scale(LeaderRuleSet.clamp(
                risk, LeaderRuleSet.ZERO, LeaderRuleSet.MAX_RISK));
    }

    private List<PotentialLeaderSnapshot.ScoreComponent> potentialComponents(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        return List.of(
                potentialDirection(row),
                potentialRelativeStrength(row),
                potentialLiquidity(row),
                potentialStructure(row),
                potentialMarketCap(row));
    }

    private List<PotentialLeaderSnapshot.ScoreComponent> competitionComponents(
            LeaderCandidateEngine.ScorableCandidate row,
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions,
            PotentialLeaderSnapshot.MarketSummary marketSummary
    ) {
        return List.of(
                competitionDirection(row),
                competitionBoardPosition(row, rows, directions),
                competitionSealQuality(row),
                competitionLiquidity(row),
                competitionRelay(marketSummary));
    }

    private PotentialLeaderSnapshot.ScoreComponent potentialDirection(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        return component("POTENTIAL_DIRECTION", "主线共振", row.direction().score(),
                row.direction().score().multiply(new BigDecimal("0.30")), "30");
    }

    private PotentialLeaderSnapshot.ScoreComponent potentialRelativeStrength(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        BigDecimal points = row.changePercentileWithinDirection().multiply(new BigDecimal("15"))
                .add(row.amountPercentileWithinDirection().multiply(new BigDecimal("10")));
        return component("POTENTIAL_RELATIVE_STRENGTH", "板块内相对强度与辨识度",
                row.changePercentileWithinDirection(), points, "25");
    }

    private PotentialLeaderSnapshot.ScoreComponent potentialLiquidity(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        BigDecimal volumeRatio = LeaderRuleSet.linear(
                row.quote().realtimeVolumeRatio(), new BigDecimal("0.8"), new BigDecimal("2.5"),
                new BigDecimal("8"));
        BigDecimal points = volumeRatio
                .add(row.turnoverPercentileWithinDirection().multiply(new BigDecimal("6")))
                .add(row.amountPercentileWithinDirection().multiply(new BigDecimal("6")));
        return component("POTENTIAL_LIQUIDITY", "量能、换手和容量",
                row.quote().realtimeVolumeRatio(), points, "20");
    }

    private PotentialLeaderSnapshot.ScoreComponent potentialStructure(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        BigDecimal breakoutRatio = breakoutRatio(row);
        BigDecimal breakout = LeaderRuleSet.linear(
                breakoutRatio, new BigDecimal("0.90"), BigDecimal.ONE, new BigDecimal("6"));
        BigDecimal movingAverage = BigDecimal.ZERO;
        if (greater(row.baseline().previousClose(), row.baseline().ma20())) {
            movingAverage = movingAverage.add(new BigDecimal("2"));
        }
        if (greater(row.baseline().ma10(), row.baseline().ma20())) {
            movingAverage = movingAverage.add(new BigDecimal("2"));
        }
        if (row.baseline().ma20Slope() != null && row.baseline().ma20Slope().signum() > 0) {
            movingAverage = movingAverage.add(BigDecimal.ONE);
        }
        BigDecimal intraday = intradayPosition(row).multiply(new BigDecimal("4"));
        return component("POTENTIAL_STRUCTURE", "突破、均线和日内结构", breakoutPosition(row),
                breakout.add(movingAverage).add(intraday), "15");
    }

    private PotentialLeaderSnapshot.ScoreComponent potentialMarketCap(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        return component("POTENTIAL_MARKET_CAP", "流通市值适配",
                row.marketCapPercentileWithinStrongDirections(),
                marketCapFit(row.marketCapPercentileWithinStrongDirections(), new BigDecimal("10")), "10");
    }

    private PotentialLeaderSnapshot.ScoreComponent competitionDirection(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        return component("COMPETITION_DIRECTION", "主线共振", row.direction().score(),
                row.direction().score().multiply(new BigDecimal("0.20")), "20");
    }

    private PotentialLeaderSnapshot.ScoreComponent competitionBoardPosition(
            LeaderCandidateEngine.ScorableCandidate row,
            List<LeaderCandidateEngine.ScorableCandidate> rows,
            List<PotentialLeaderSnapshot.Direction> directions
    ) {
        int globalMax = Math.max(1, directions == null ? 1 : directions.stream()
                .mapToInt(PotentialLeaderSnapshot.Direction::maxBoard).max().orElse(1));
        BigDecimal points = row.boardState().currentlySealed() ? new BigDecimal("8") : BigDecimal.ZERO;
        int boards = Math.max(1, row.boardState().consecutiveBoards());
        double normalized = Math.log1p(boards) / Math.log1p(globalMax);
        points = points.add(BigDecimal.valueOf(Math.min(1D, normalized)).multiply(new BigDecimal("10")));
        List<LeaderCandidateEngine.ScorableCandidate> industryRows = rows.stream()
                .filter(peer -> peer.direction().industry().equals(row.direction().industry()))
                .toList();
        long higher = industryRows.stream()
                .filter(peer -> peer.boardState().consecutiveBoards() > row.boardState().consecutiveBoards())
                .count();
        if (higher < 2) {
            points = points.add(new BigDecimal("4"));
        }
        int industryMax = industryRows.stream()
                .mapToInt(peer -> peer.boardState().consecutiveBoards()).max().orElse(0);
        long maxCount = industryRows.stream()
                .filter(peer -> peer.boardState().consecutiveBoards() == industryMax).count();
        if (row.boardState().consecutiveBoards() == industryMax && maxCount == 1) {
            points = points.add(new BigDecimal("3"));
        }
        return component("COMPETITION_BOARD_POSITION", "稀缺性和板位",
                BigDecimal.valueOf(boards), points, "25");
    }

    private PotentialLeaderSnapshot.ScoreComponent competitionSealQuality(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        BigDecimal timePoints = sealTimePoints(row.boardState().firstSealTime());
        BigDecimal breakPoints = switch (row.boardState().sealBreakCount()) {
            case 0 -> new BigDecimal("6");
            case 1 -> new BigDecimal("4");
            case 2 -> new BigDecimal("2");
            default -> BigDecimal.ZERO;
        };
        BigDecimal sealRatio = BigDecimal.ZERO;
        if (row.boardState().currentlySealed() && positive(row.boardState().sealFunds())
                && positive(row.quote().amount())) {
            sealRatio = row.boardState().sealFunds().divide(row.quote().amount(), 8, RoundingMode.HALF_UP);
        }
        BigDecimal sealPoints = LeaderRuleSet.linear(
                sealRatio, BigDecimal.ZERO, new BigDecimal("0.10"), new BigDecimal("6"));
        return component("COMPETITION_SEAL_QUALITY", "封板时点、持续性和炸板",
                sealRatio, timePoints.add(breakPoints).add(sealPoints), "20");
    }

    private PotentialLeaderSnapshot.ScoreComponent competitionLiquidity(
            LeaderCandidateEngine.ScorableCandidate row
    ) {
        BigDecimal turnoverPoints = turnoverSweetSpot(row.quote().turnoverRate());
        BigDecimal amountPoints = row.amountPercentileWithinDirection().multiply(new BigDecimal("5"));
        BigDecimal capPoints = marketCapFit(
                row.marketCapPercentileWithinStrongDirections(), new BigDecimal("4"));
        return component("COMPETITION_LIQUIDITY", "换手、成交额和流通市值适配",
                row.quote().turnoverRate(), turnoverPoints.add(amountPoints).add(capPoints), "15");
    }

    private PotentialLeaderSnapshot.ScoreComponent competitionRelay(
            PotentialLeaderSnapshot.MarketSummary market
    ) {
        if (market == null) {
            return component("COMPETITION_RELAY", "市场接力环境", null, BigDecimal.ZERO, "10");
        }
        BigDecimal seal = LeaderRuleSet.linear(
                market.sealRatePercent(), new BigDecimal("40"), new BigDecimal("90"),
                new BigDecimal("4"));
        BigDecimal height = LeaderRuleSet.linear(
                BigDecimal.valueOf(market.maxBoard()), BigDecimal.ONE, new BigDecimal("6"),
                new BigDecimal("3"));
        BigDecimal boards = LeaderRuleSet.linear(
                market.boards2PlusRatioPercent(), BigDecimal.ZERO, new BigDecimal("40"),
                new BigDecimal("3"));
        return component("COMPETITION_RELAY", "市场接力环境", market.sealRatePercent(),
                seal.add(height).add(boards), "10");
    }

    private List<PotentialLeaderSnapshot.RiskDeduction> risks(
            LeaderCandidateEngine.ScorableCandidate row,
            PotentialLeaderSnapshot.DragonTigerEvidence evidence,
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections
    ) {
        List<PotentialLeaderSnapshot.RiskDeduction> risks = new ArrayList<>();
        if (row.direction().limitUpCount() == 1
                && row.direction().breadthPercent().compareTo(new BigDecimal("50")) < 0) {
            risks.add(risk("THIN_DIRECTION", "行业涨停单薄且广度不足",
                    row.direction().breadthPercent(), new BigDecimal("50"), "6"));
        }
        if (row.boardState().sealBreakCount() >= 3) {
            risks.add(risk("REPEATED_BREAK", "反复炸板",
                    BigDecimal.valueOf(row.boardState().sealBreakCount()), new BigDecimal("3"), "6"));
        } else if (row.boardState().sealBreakCount() == 2) {
            risks.add(risk("REPEATED_BREAK", "两次炸板",
                    BigDecimal.valueOf(row.boardState().sealBreakCount()), new BigDecimal("2"), "3"));
        }
        if (row.boardState().firstSealTime() != null
                && row.boardState().firstSealTime().isAfter(LATE_SEAL)) {
            risks.add(risk("LATE_SEAL", "14:30 后首次封板",
                    BigDecimal.valueOf(row.boardState().firstSealTime().toSecondOfDay()),
                    BigDecimal.valueOf(LATE_SEAL.toSecondOfDay()), "5"));
        }
        if (row.quote().turnoverRate().compareTo(new BigDecimal("25")) > 0
                || row.turnoverPercentileWithinMarket().compareTo(new BigDecimal("0.95")) >= 0) {
            risks.add(risk("EXTREME_TURNOVER", "极端换手",
                    row.quote().turnoverRate(), new BigDecimal("25"), "5"));
        }
        if (row.quote().realtimeVolumeRatio() != null
                && row.quote().realtimeVolumeRatio().compareTo(new BigDecimal("2.5")) >= 0
                && row.changePercentileWithinDirection().compareTo(new BigDecimal("0.50")) < 0) {
            risks.add(risk("VOLUME_PRICE_DIVERGENCE", "放量但板块内涨幅落后",
                    row.quote().realtimeVolumeRatio(), new BigDecimal("2.5"), "5"));
        }
        BigDecimal upperShadow = currentUpperShadow(row);
        if (upperShadow != null && upperShadow.compareTo(new BigDecimal("0.50")) >= 0) {
            risks.add(risk("LONG_UPPER_SHADOW", "当日上影偏长",
                    upperShadow, new BigDecimal("0.50"), "4"));
        }
        PotentialLeaderSnapshot.Direction previous = previousDirections.get(row.direction().industry());
        if (previous != null) {
            BigDecimal scoreDrop = previous.score().subtract(row.direction().score());
            boolean boardDrop = previous.limitUpCount() > 0
                    && BigDecimal.valueOf(row.direction().limitUpCount())
                    .compareTo(BigDecimal.valueOf(previous.limitUpCount())
                            .multiply(new BigDecimal("0.70"))) <= 0;
            if (scoreDrop.compareTo(new BigDecimal("15")) >= 0 || boardDrop) {
                risks.add(risk("DIRECTION_FADING", "同日行业强度转弱",
                        scoreDrop.max(BigDecimal.ZERO), new BigDecimal("15"), "5"));
            }
        }
        BigDecimal concentration = buyConcentration(evidence);
        if (concentration != null && concentration.compareTo(new BigDecimal("0.50")) >= 0) {
            risks.add(risk("SEAT_CONCENTRATION", "第一买入营业部占比过高",
                    concentration, new BigDecimal("0.50"), "4"));
        }
        return List.copyOf(risks);
    }

    private PotentialLeaderSnapshot.ScoreComponent component(
            String code, String label, BigDecimal raw, BigDecimal points, String maximum
    ) {
        return new PotentialLeaderSnapshot.ScoreComponent(
                code, label, raw == null ? null : LeaderRuleSet.scale(raw),
                LeaderRuleSet.scale(points), new BigDecimal(maximum));
    }

    private PotentialLeaderSnapshot.RiskDeduction risk(
            String code, String label, BigDecimal raw, BigDecimal threshold, String points
    ) {
        return new PotentialLeaderSnapshot.RiskDeduction(
                code, label, LeaderRuleSet.scale(raw), LeaderRuleSet.scale(threshold),
                new BigDecimal(points).setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal marketCapFit(BigDecimal percentile, BigDecimal maximum) {
        if (percentile == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal outer = maximum.multiply(new BigDecimal("0.30"));
        BigDecimal shoulder = maximum.multiply(new BigDecimal("0.60"));
        if (percentile.compareTo(new BigDecimal("0.20")) >= 0
                && percentile.compareTo(new BigDecimal("0.70")) <= 0) {
            return LeaderRuleSet.scale(maximum);
        }
        if (percentile.compareTo(new BigDecimal("0.10")) >= 0
                && percentile.compareTo(new BigDecimal("0.20")) < 0) {
            BigDecimal rise = percentile.subtract(new BigDecimal("0.10"))
                    .divide(new BigDecimal("0.10"), 8, RoundingMode.HALF_UP);
            return LeaderRuleSet.scale(shoulder.add(maximum.subtract(shoulder).multiply(rise)));
        }
        if (percentile.compareTo(new BigDecimal("0.70")) > 0
                && percentile.compareTo(new BigDecimal("0.85")) <= 0) {
            BigDecimal fall = percentile.subtract(new BigDecimal("0.70"))
                    .divide(new BigDecimal("0.15"), 8, RoundingMode.HALF_UP);
            return LeaderRuleSet.scale(maximum.subtract(maximum.subtract(shoulder).multiply(fall)));
        }
        return LeaderRuleSet.scale(outer);
    }

    private BigDecimal turnoverSweetSpot(BigDecimal turnover) {
        if (turnover == null || turnover.compareTo(new BigDecimal("0.30")) < 0
                || turnover.compareTo(new BigDecimal("30")) > 0) {
            return BigDecimal.ZERO;
        }
        if (turnover.compareTo(new BigDecimal("3")) < 0) {
            return LeaderRuleSet.linear(
                    turnover, new BigDecimal("0.30"), new BigDecimal("3"), new BigDecimal("6"));
        }
        if (turnover.compareTo(new BigDecimal("18")) <= 0) {
            return new BigDecimal("6");
        }
        BigDecimal distance = turnover.subtract(new BigDecimal("18"));
        return LeaderRuleSet.scale(new BigDecimal("6").subtract(
                distance.multiply(new BigDecimal("6"))
                        .divide(new BigDecimal("12"), 8, RoundingMode.HALF_UP)));
    }

    private BigDecimal sealTimePoints(LocalTime time) {
        if (time == null || !time.isBefore(LATE_SEAL)) {
            return BigDecimal.ZERO;
        }
        if (!time.isAfter(EARLY_SEAL)) {
            return new BigDecimal("8");
        }
        long total = Duration.between(EARLY_SEAL, LATE_SEAL).toMinutes();
        long elapsed = Duration.between(EARLY_SEAL, time).toMinutes();
        return LeaderRuleSet.scale(BigDecimal.valueOf(total - elapsed)
                .multiply(new BigDecimal("8"))
                .divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal breakoutRatio(LeaderCandidateEngine.ScorableCandidate row) {
        BigDecimal maxClose = row.baseline().recentBars().stream()
                .filter(bar -> bar != null && positive(bar.close()))
                .sorted(Comparator.comparing(com.aistock.research.firstboard.FirstBoardBar::tradeDate).reversed())
                .limit(20)
                .map(com.aistock.research.firstboard.FirstBoardBar::close)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (!positive(maxClose) || !positive(row.quote().latestPrice())) {
            return BigDecimal.ZERO;
        }
        return row.quote().latestPrice().divide(maxClose, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal breakoutPosition(LeaderCandidateEngine.ScorableCandidate row) {
        return LeaderRuleSet.scale(breakoutRatio(row).multiply(LeaderRuleSet.HUNDRED));
    }

    private BigDecimal intradayPosition(LeaderCandidateEngine.ScorableCandidate row) {
        if (row.quote().highPrice() == null || row.quote().lowPrice() == null
                || row.quote().latestPrice() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal range = row.quote().highPrice().subtract(row.quote().lowPrice());
        if (range.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return LeaderRuleSet.clamp(
                row.quote().latestPrice().subtract(row.quote().lowPrice())
                        .divide(range, 8, RoundingMode.HALF_UP),
                BigDecimal.ZERO, BigDecimal.ONE);
    }

    private BigDecimal currentUpperShadow(LeaderCandidateEngine.ScorableCandidate row) {
        if (row.quote().highPrice() == null || row.quote().lowPrice() == null
                || row.quote().openPrice() == null || row.quote().latestPrice() == null) {
            return null;
        }
        BigDecimal range = row.quote().highPrice().subtract(row.quote().lowPrice());
        if (range.signum() <= 0) {
            return null;
        }
        BigDecimal bodyTop = row.quote().openPrice().max(row.quote().latestPrice());
        return LeaderRuleSet.clamp(
                row.quote().highPrice().subtract(bodyTop)
                        .divide(range, 8, RoundingMode.HALF_UP),
                BigDecimal.ZERO, BigDecimal.ONE);
    }

    private BigDecimal buyConcentration(PotentialLeaderSnapshot.DragonTigerEvidence evidence) {
        if (evidence == null || evidence.status() != PotentialLeaderSnapshot.DisclosureStatus.DISCLOSED) {
            return null;
        }
        List<BigDecimal> buys = evidence.seats().stream()
                .filter(seat -> "BUY".equalsIgnoreCase(seat.side()))
                .map(PotentialLeaderSnapshot.Seat::buyAmount)
                .filter(LeaderScoreEngine::positive)
                .toList();
        BigDecimal total = buys.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return null;
        }
        return buys.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO)
                .divide(total, 8, RoundingMode.HALF_UP);
    }

    private List<String> evidence(
            LeaderCandidateEngine.ScorableCandidate row,
            List<PotentialLeaderSnapshot.ScoreComponent> components,
            List<PotentialLeaderSnapshot.RiskDeduction> risks,
            BigDecimal seatAdjustment
    ) {
        List<String> messages = new ArrayList<>();
        messages.add("所属强方向第 " + row.direction().rank() + " 名，方向分 "
                + row.direction().score().toPlainString());
        components.stream().max(Comparator.comparing(PotentialLeaderSnapshot.ScoreComponent::points))
                .ifPresent(component -> messages.add("最高贡献：" + component.label() + " +"
                        + component.points().toPlainString()));
        if (row.lane() == PotentialLeaderSnapshot.Lane.COMPETITION) {
            messages.add("龙虎榜软调整 " + signed(seatAdjustment));
        }
        if (risks.isEmpty()) {
            messages.add("未触发规则化风险扣分");
        }
        return List.copyOf(messages);
    }

    private String snapshotHash(
            LeaderCandidateEngine.ScorableCandidate row,
            BigDecimal score,
            List<PotentialLeaderSnapshot.ScoreComponent> components,
            List<PotentialLeaderSnapshot.RiskDeduction> risks,
            PotentialLeaderSnapshot.DragonTigerEvidence dragonTiger
    ) {
        StringBuilder canonical = new StringBuilder(LeaderRuleSet.RULE_VERSION)
                .append('|').append(row.lane())
                .append('|').append(row.quote().symbol())
                .append('|').append(value(score))
                .append('|').append(value(row.quote().latestPrice()))
                .append('|').append(value(row.quote().changePercent()))
                .append('|').append(value(row.quote().amount()))
                .append('|').append(value(row.quote().turnoverRate()))
                .append('|').append(value(row.quote().circulatingMarketValue()))
                .append('|').append(row.baseline().baselineDate())
                .append('|').append(row.direction().industry())
                .append('|').append(value(row.direction().score()))
                .append('|').append(row.boardState().currentlySealed())
                .append('|').append(row.boardState().consecutiveBoards())
                .append('|').append(row.boardState().firstSealTime())
                .append('|').append(row.boardState().lastSealTime())
                .append('|').append(row.boardState().sealBreakCount())
                .append('|').append(value(row.boardState().sealFunds()))
                .append('|').append(dragonTiger.status())
                .append('|').append(dragonTiger.disclosureDate())
                .append('|').append(value(dragonTiger.adjustment()));
        components.forEach(component -> canonical.append('|').append(component.code())
                .append('=').append(value(component.points())));
        risks.forEach(risk -> canonical.append('|').append(risk.code())
                .append('=').append(value(risk.points())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private PotentialLeaderSnapshot.DragonTigerEvidence safeEvidence(
            PotentialLeaderSnapshot.DragonTigerEvidence evidence
    ) {
        return evidence == null ? noDragonTigerEvidence() : new PotentialLeaderSnapshot.DragonTigerEvidence(
                evidence.status(), evidence.disclosureDate(), LeaderRuleSet.scale(LeaderRuleSet.clamp(
                        evidence.status() == PotentialLeaderSnapshot.DisclosureStatus.DISCLOSED
                                ? evidence.adjustment() : BigDecimal.ZERO,
                        LeaderRuleSet.MIN_SEAT_ADJUSTMENT, LeaderRuleSet.MAX_SEAT_ADJUSTMENT)),
                evidence.seats(), evidence.dataGaps());
    }

    private PotentialLeaderSnapshot.DragonTigerEvidence noDragonTigerEvidence() {
        return new PotentialLeaderSnapshot.DragonTigerEvidence(
                PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED, null,
                BigDecimal.ZERO.setScale(2), List.of(), List.of());
    }

    private PotentialLeaderSnapshot.Candidate withRank(
            PotentialLeaderSnapshot.Candidate source, int rank
    ) {
        return new PotentialLeaderSnapshot.Candidate(
                source.lane(), rank, source.grade(), source.score(), source.symbol(), source.name(),
                source.industry(), source.board(), source.latestPrice(), source.changePercent(),
                source.realtimeVolumeRatio(), source.turnoverRate(), source.amount(),
                source.circulatingMarketValue(), source.ma10(), source.ma20(), source.ma20Slope(),
                source.breakoutPositionPercent(), source.currentlySealed(), source.consecutiveBoards(),
                source.firstSealTime(), source.lastSealTime(), source.sealBreakCount(), source.sealFunds(),
                source.scoreBreakdown(), source.riskDeductions(), source.evidence(), source.dragonTiger(),
                source.calibration(), source.snapshotHash());
    }

    private int directionRank(String industry, List<PotentialLeaderSnapshot.Direction> directions) {
        return safe(directions).stream()
                .filter(direction -> direction.industry().equals(industry))
                .map(PotentialLeaderSnapshot.Direction::rank)
                .findFirst().orElse(Integer.MAX_VALUE);
    }

    private static boolean greater(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) > 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String value(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal value) {
        String text = LeaderRuleSet.scale(value).toPlainString();
        return value.signum() > 0 ? "+" + text : text;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record Draft(PotentialLeaderSnapshot.Candidate candidate, BigDecimal risk) {
    }
}
