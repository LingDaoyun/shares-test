package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardBar;
import com.aistock.research.firstboard.FirstBoardBoard;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineAccess;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermBrokenBoardStock;
import com.aistock.research.shortterm.ShortTermLimitUpBoardSnapshot;
import com.aistock.research.shortterm.ShortTermLimitUpStock;
import com.aistock.research.trading.AsharePriceLimitRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LeaderCandidateEngine {

    public CandidateUniverse select(
            FirstBoardLeaderBaselineAccess baseline,
            List<EastMoneyQuote> quotes,
            ShortTermLimitUpBoardSnapshot boards,
            List<PotentialLeaderSnapshot.Direction> directions,
            boolean includeGrowth,
            boolean includeStar
    ) {
        return select(new SelectionInput(
                baseline.tradeDate(), baseline, quotes, directions,
                boards.stocks(), boards.brokenStocks(), includeGrowth, includeStar));
    }

    CandidateUniverse select(SelectionInput input) {
        List<EastMoneyQuote> quotes = safe(input.quotes());
        Map<String, FirstBoardLeaderBaselineStock> baselines = input.baseline().stocks();
        Map<String, PotentialLeaderSnapshot.Direction> directions = new LinkedHashMap<>();
        for (PotentialLeaderSnapshot.Direction direction : safe(input.directions())) {
            directions.put(LeaderDirectionEngine.normalizeIndustry(direction.industry()), direction);
        }
        Map<String, BoardState> boardStates = boardStates(input.sealed(), input.broken());
        List<BigDecimal> marketAmounts = quotes.stream()
                .filter(quote -> quote != null && input.tradeDate().equals(quote.tradeDate()))
                .map(EastMoneyQuote::amount)
                .filter(LeaderCandidateEngine::positive)
                .toList();
        List<BigDecimal> marketTurnovers = quotes.stream()
                .filter(quote -> quote != null && input.tradeDate().equals(quote.tradeDate()))
                .map(EastMoneyQuote::turnoverRate)
                .filter(LeaderCandidateEngine::positive)
                .toList();

        int scopedCount = 0;
        int liquidCount = 0;
        int excludedMissingDataCount = 0;
        List<EligibleRow> eligible = new ArrayList<>();
        List<PeerRow> strongDirectionPeers = new ArrayList<>();
        for (EastMoneyQuote quote : quotes) {
            if (quote == null || riskyName(quote.name())) {
                continue;
            }
            String symbol = LeaderDirectionEngine.normalizeSymbol(quote.symbol());
            FirstBoardLeaderBaselineStock baseline = baselines.get(symbol);
            if (baseline == null) {
                baseline = baselines.get(quote.symbol());
            }
            FirstBoardBoard board = baseline == null ? FirstBoardBoard.fromSymbol(symbol) : baseline.board();
            if (!enabled(board, input.includeGrowth(), input.includeStar())) {
                continue;
            }
            scopedCount++;
            if (baseline == null || !complete(input.tradeDate(), quote, baseline)) {
                excludedMissingDataCount++;
                continue;
            }
            BigDecimal amountPercentile = LeaderRuleSet.percentile(quote.amount(), marketAmounts);
            if (amountPercentile.compareTo(LeaderRuleSet.MIN_AMOUNT_PERCENTILE) < 0
                    || quote.turnoverRate().compareTo(LeaderRuleSet.MIN_TURNOVER) < 0) {
                continue;
            }
            liquidCount++;
            PotentialLeaderSnapshot.Direction direction = directions.get(
                    LeaderDirectionEngine.normalizeIndustry(quote.industry()));
            if (direction == null) {
                continue;
            }
            strongDirectionPeers.add(new PeerRow(quote, direction));
            BoardState boardState = boardStates.get(symbol);
            boolean touched = AsharePriceLimitRule.hasTouchedLimitUp(
                    symbol, baseline.previousClose(), quote.highPrice());
            PotentialLeaderSnapshot.Lane lane;
            if (boardState != null) {
                lane = PotentialLeaderSnapshot.Lane.COMPETITION;
            } else if (!touched && positive(quote.realtimeVolumeRatio())) {
                lane = PotentialLeaderSnapshot.Lane.POTENTIAL;
                boardState = BoardState.empty();
            } else {
                continue;
            }
            eligible.add(new EligibleRow(lane, quote, baseline, direction, boardState));
        }

        Map<String, List<PeerRow>> byIndustry = new LinkedHashMap<>();
        strongDirectionPeers.forEach(row -> byIndustry
                .computeIfAbsent(row.direction().industry(), ignored -> new ArrayList<>()).add(row));
        List<BigDecimal> strongCaps = strongDirectionPeers.stream()
                .map(row -> row.quote().circulatingMarketValue())
                .toList();
        List<ScorableCandidate> potential = new ArrayList<>();
        List<ScorableCandidate> competition = new ArrayList<>();
        for (EligibleRow row : eligible) {
            List<PeerRow> peers = byIndustry.get(row.direction().industry());
            List<BigDecimal> peerAmounts = peers.stream().map(peer -> peer.quote().amount()).toList();
            List<BigDecimal> peerChanges = peers.stream().map(peer -> peer.quote().changePercent()).toList();
            List<BigDecimal> peerTurnovers = peers.stream().map(peer -> peer.quote().turnoverRate()).toList();
            ScorableCandidate scorable = new ScorableCandidate(
                    row.lane(), row.quote(), row.baseline(), row.direction(), row.boardState(),
                    LeaderRuleSet.percentile(row.quote().amount(), peerAmounts),
                    LeaderRuleSet.percentile(row.quote().changePercent(), peerChanges),
                    LeaderRuleSet.percentile(row.quote().turnoverRate(), peerTurnovers),
                    LeaderRuleSet.percentile(row.quote().circulatingMarketValue(), strongCaps),
                    LeaderRuleSet.percentile(row.quote().turnoverRate(), marketTurnovers));
            if (row.lane() == PotentialLeaderSnapshot.Lane.POTENTIAL) {
                potential.add(scorable);
            } else {
                competition.add(scorable);
            }
        }
        SelectionCounts counts = new SelectionCounts(
                quotes.size(), baselines.size(), scopedCount, liquidCount, directions.size(),
                potential.size(), competition.size(), excludedMissingDataCount);
        return new CandidateUniverse(potential, competition, counts);
    }

    private Map<String, BoardState> boardStates(
            List<ShortTermLimitUpStock> sealed,
            List<ShortTermBrokenBoardStock> broken
    ) {
        Map<String, BoardState> result = new LinkedHashMap<>();
        for (ShortTermLimitUpStock stock : safe(sealed)) {
            String symbol = LeaderDirectionEngine.normalizeSymbol(stock.symbol());
            result.merge(symbol, new BoardState(
                    true, Math.max(1, stock.consecutiveBoards()), stock.firstSealTime(),
                    stock.lastSealTime(), stock.sealBreakCount(), stock.sealFunds()),
                    LeaderCandidateEngine::mergeBoardState);
        }
        for (ShortTermBrokenBoardStock stock : safe(broken)) {
            String symbol = LeaderDirectionEngine.normalizeSymbol(stock.symbol());
            result.merge(symbol, new BoardState(
                    false, 1, stock.firstSealTime(), null, stock.sealBreakCount(), BigDecimal.ZERO),
                    LeaderCandidateEngine::mergeBoardState);
        }
        return result;
    }

    private static BoardState mergeBoardState(BoardState left, BoardState right) {
        return new BoardState(
                left.currentlySealed() || right.currentlySealed(),
                Math.max(left.consecutiveBoards(), right.consecutiveBoards()),
                earliest(left.firstSealTime(), right.firstSealTime()),
                latest(left.lastSealTime(), right.lastSealTime()),
                Math.max(left.sealBreakCount(), right.sealBreakCount()),
                max(left.sealFunds(), right.sealFunds()));
    }

    private boolean complete(
            LocalDate tradeDate,
            EastMoneyQuote quote,
            FirstBoardLeaderBaselineStock baseline
    ) {
        return tradeDate != null && tradeDate.equals(quote.tradeDate())
                && positive(quote.latestPrice())
                && quote.changePercent() != null
                && positive(quote.volume())
                && positive(quote.amount())
                && positive(quote.turnoverRate())
                && positive(quote.circulatingMarketValue())
                && positive(quote.openPrice())
                && positive(quote.highPrice())
                && positive(quote.lowPrice())
                && positive(baseline.previousClose())
                // 已发布的 v2 基线只可能由至少 60 根有效日线生成；门面保留最近 20 根供突破计算。
                && baseline.recentBars().stream().filter(LeaderCandidateEngine::validBar).count() >= 20;
    }

    private static boolean validBar(FirstBoardBar bar) {
        return bar != null && bar.tradeDate() != null && positive(bar.close());
    }

    private static boolean enabled(FirstBoardBoard board, boolean includeGrowth, boolean includeStar) {
        return board == FirstBoardBoard.MAIN
                || (board == FirstBoardBoard.GROWTH && includeGrowth)
                || (board == FirstBoardBoard.STAR && includeStar);
    }

    private static boolean riskyName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String normalized = name.toUpperCase(Locale.ROOT).replace(" ", "");
        return normalized.contains("ST") || normalized.contains("退市") || normalized.contains("退");
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static LocalTime earliest(LocalTime left, LocalTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static LocalTime latest(LocalTime left, LocalTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.max(right);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    record SelectionInput(
            LocalDate tradeDate,
            FirstBoardLeaderBaselineAccess baseline,
            List<EastMoneyQuote> quotes,
            List<PotentialLeaderSnapshot.Direction> directions,
            List<ShortTermLimitUpStock> sealed,
            List<ShortTermBrokenBoardStock> broken,
            boolean includeGrowth,
            boolean includeStar
    ) {
        SelectionInput {
            quotes = List.copyOf(safe(quotes));
            directions = List.copyOf(safe(directions));
            sealed = List.copyOf(safe(sealed));
            broken = List.copyOf(safe(broken));
        }
    }

    record BoardState(
            boolean currentlySealed,
            int consecutiveBoards,
            LocalTime firstSealTime,
            LocalTime lastSealTime,
            int sealBreakCount,
            BigDecimal sealFunds
    ) {
        static BoardState empty() {
            return new BoardState(false, 0, null, null, 0, BigDecimal.ZERO);
        }
    }

    record ScorableCandidate(
            PotentialLeaderSnapshot.Lane lane,
            EastMoneyQuote quote,
            FirstBoardLeaderBaselineStock baseline,
            PotentialLeaderSnapshot.Direction direction,
            BoardState boardState,
            BigDecimal amountPercentileWithinDirection,
            BigDecimal changePercentileWithinDirection,
            BigDecimal turnoverPercentileWithinDirection,
            BigDecimal marketCapPercentileWithinStrongDirections,
            BigDecimal turnoverPercentileWithinMarket
    ) {
    }

    record SelectionCounts(
            int universeCount,
            int baselineCount,
            int scopedCount,
            int liquidCount,
            int strongDirectionCount,
            int potentialEligibleCount,
            int competitionEligibleCount,
            int excludedMissingDataCount
    ) {
    }

    record CandidateUniverse(
            List<ScorableCandidate> potential,
            List<ScorableCandidate> competition,
            SelectionCounts counts
    ) {
        CandidateUniverse {
            potential = List.copyOf(potential);
            competition = List.copyOf(competition);
        }

        List<String> competitionSymbols() {
            return competition.stream().map(row -> row.quote().symbol()).distinct().toList();
        }
    }

    private record EligibleRow(
            PotentialLeaderSnapshot.Lane lane,
            EastMoneyQuote quote,
            FirstBoardLeaderBaselineStock baseline,
            PotentialLeaderSnapshot.Direction direction,
            BoardState boardState
    ) {
    }

    private record PeerRow(
            EastMoneyQuote quote,
            PotentialLeaderSnapshot.Direction direction
    ) {
    }
}
