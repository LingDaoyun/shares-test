package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.firstboard.FirstBoardMarketDayStatus;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LeaderLearningService {

    private static final LocalTime FIXED_CLOSE_SAMPLE = LocalTime.of(15, 10);
    private static final int MIN_CALIBRATION_SAMPLES = 30;
    private static final double WILSON_Z = 1.96D;

    private final LeaderSnapshotStore snapshotStore;
    private final FirstBoardDailyBarStore dailyBars;
    private final TradingClockService tradingClock;

    public LeaderLearningService(
            LeaderSnapshotStore snapshotStore,
            FirstBoardDailyBarStore dailyBars,
            TradingClockService tradingClock
    ) {
        this.snapshotStore = snapshotStore;
        this.dailyBars = dailyBars;
        this.tradingClock = tradingClock;
    }

    public Optional<UUID> capture(PotentialLeaderSnapshot snapshot) {
        if (snapshot == null || snapshot.status() != PotentialLeaderSnapshot.Status.READY
                || snapshot.tradeDate() == null || !snapshot.tradeDate().equals(snapshot.quoteTradeDate())
                || snapshot.quoteTimestamp() == null) {
            return Optional.empty();
        }
        LocalDateTime now = tradingClock.currentMarketDateTime();
        if (now.toLocalDate().isBefore(snapshot.tradeDate())
                || (now.toLocalDate().equals(snapshot.tradeDate())
                && now.toLocalTime().isBefore(FIXED_CLOSE_SAMPLE))) {
            return Optional.empty();
        }
        if (now.toLocalDate().isAfter(snapshot.tradeDate()) && hasCatchUpLookahead(snapshot)) {
            return Optional.empty();
        }
        UUID snapshotId = snapshotStore.saveSnapshotIfAbsent(snapshot);
        snapshotStore.initializeOutcomes(snapshotId, snapshot);
        return Optional.of(snapshotId);
    }

    public void refreshPending(LocalDate evaluatedAsOf) {
        LocalDate asOf = evaluatedAsOf == null ? tradingClock.currentMarketDate() : evaluatedAsOf;
        for (LeaderSnapshotStore.PendingOutcome pending : snapshotStore.pendingOutcomes()) {
            try {
                snapshotStore.saveOutcome(evaluate(pending, asOf));
            } catch (RuntimeException exception) {
                snapshotStore.saveOutcome(pending(pending, asOf));
            }
        }
    }

    public PotentialLeaderSnapshot.Calibration calibration(
            PotentialLeaderSnapshot.Lane lane,
            PotentialLeaderSnapshot.Grade grade,
            BigDecimal directionScore
    ) {
        String heat = directionScore != null && directionScore.compareTo(new BigDecimal("75")) >= 0
                ? "HOT" : "WARM";
        LeaderSnapshotStore.CohortCounts counts = snapshotStore.cohortCounts(lane, grade, heat);
        if (counts.sampleSize() < MIN_CALIBRATION_SAMPLES) {
            return new PotentialLeaderSnapshot.Calibration(
                    PotentialLeaderSnapshot.CalibrationStatus.COLLECTING,
                    counts.sampleSize(), null, null, null);
        }
        double n = counts.sampleSize();
        double proportion = counts.successCount() / n;
        double zSquared = WILSON_Z * WILSON_Z;
        double denominator = 1D + zSquared / n;
        double center = (proportion + zSquared / (2D * n)) / denominator;
        double margin = WILSON_Z * Math.sqrt(
                (proportion * (1D - proportion) + zSquared / (4D * n)) / n) / denominator;
        return new PotentialLeaderSnapshot.Calibration(
                PotentialLeaderSnapshot.CalibrationStatus.READY,
                counts.sampleSize(), percent(proportion), percent(Math.max(0D, center - margin)),
                percent(Math.min(1D, center + margin)));
    }

    private LeaderSnapshotStore.OutcomeResult evaluate(
            LeaderSnapshotStore.PendingOutcome pending,
            LocalDate asOf
    ) {
        int horizon = pending.lane() == PotentialLeaderSnapshot.Lane.POTENTIAL ? 5 : 3;
        List<LocalDate> sessions = new ArrayList<>();
        for (int offset = 1; offset <= horizon; offset++) {
            Optional<LocalDate> date = tradingClock.verifiedTradingDayAfter(pending.tradeDate(), offset);
            if (date.isEmpty()) {
                return pending(pending, asOf);
            }
            sessions.add(date.get());
        }
        LocalDate horizonDate = sessions.get(sessions.size() - 1);
        if (asOf.isBefore(horizonDate) || sessions.stream().anyMatch(date -> !ready(date))) {
            return pending(pending, asOf);
        }
        Set<String> symbols = new LinkedHashSet<>(pending.directionConstituents().stream()
                .map(LeaderDirectionEngine::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .toList());
        symbols.add(LeaderDirectionEngine.normalizeSymbol(pending.symbol()));
        if (pending.industry() == null || symbols.isEmpty() || !positive(pending.sampleClose())) {
            return pending(pending, asOf);
        }
        Map<LocalDate, Map<String, BigDecimal>> closes = new LinkedHashMap<>();
        List<LocalDate> allDates = new ArrayList<>();
        allDates.add(pending.tradeDate());
        allDates.addAll(sessions);
        for (LocalDate date : allDates) {
            Map<String, BigDecimal> day = normalizedPrices(dailyBars.closingPrices(symbols, date));
            if (!completePrices(symbols, day)) {
                return pending(pending, asOf);
            }
            closes.put(date, day);
        }
        return pending.lane() == PotentialLeaderSnapshot.Lane.POTENTIAL
                ? potentialOutcome(pending, sessions, closes)
                : competitionOutcome(pending, sessions, closes);
    }

    private LeaderSnapshotStore.OutcomeResult potentialOutcome(
            LeaderSnapshotStore.PendingOutcome pending,
            List<LocalDate> sessions,
            Map<LocalDate, Map<String, BigDecimal>> closes
    ) {
        String symbol = LeaderDirectionEngine.normalizeSymbol(pending.symbol());
        List<BigDecimal> candidateCloses = sessions.stream()
                .map(date -> closes.get(date).get(symbol)).toList();
        List<BigDecimal> peerMaximumReturns = new ArrayList<>();
        for (String peer : closes.get(pending.tradeDate()).keySet()) {
            BigDecimal base = closes.get(pending.tradeDate()).get(peer);
            BigDecimal maximum = sessions.stream()
                    .map(date -> cumulativeReturn(base, closes.get(date).get(peer)))
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            peerMaximumReturns.add(maximum);
        }
        BigDecimal candidateMaximum = candidateCloses.stream()
                .map(close -> cumulativeReturn(pending.sampleClose(), close))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean topTwentyPercent = LeaderRuleSet.percentile(candidateMaximum, peerMaximumReturns)
                .compareTo(new BigDecimal("0.80")) >= 0;
        int closedLimitUps = (int) sessions.stream()
                .filter(date -> dailyBars.confirmedLimitUpSymbols(date).contains(symbol)).count();
        boolean success = closedLimitUps > 0 && topTwentyPercent;
        return ready(
                pending, success,
                cumulativeReturn(pending.sampleClose(), candidateCloses.get(0)),
                cumulativeReturn(pending.sampleClose(), candidateCloses.get(2)),
                cumulativeReturn(pending.sampleClose(), candidateCloses.get(4)),
                maxDrawdown(pending.sampleClose(), candidateCloses), closedLimitUps, null,
                sessions.get(4));
    }

    private LeaderSnapshotStore.OutcomeResult competitionOutcome(
            LeaderSnapshotStore.PendingOutcome pending,
            List<LocalDate> sessions,
            Map<LocalDate, Map<String, BigDecimal>> closes
    ) {
        String symbol = LeaderDirectionEngine.normalizeSymbol(pending.symbol());
        Map<String, Integer> streaks = new LinkedHashMap<>();
        Set<String> sampleLimitUps = normalizedSymbols(dailyBars.confirmedLimitUpSymbols(pending.tradeDate()));
        closes.get(pending.tradeDate()).keySet().forEach(peer ->
                streaks.put(peer, sampleLimitUps.contains(peer) ? 1 : 0));
        boolean success = false;
        int closedLimitUps = 0;
        int candidateMaxBoard = streaks.getOrDefault(symbol, 0);
        for (LocalDate session : sessions) {
            Set<String> limitUps = normalizedSymbols(dailyBars.confirmedLimitUpSymbols(session));
            for (String peer : streaks.keySet()) {
                streaks.put(peer, limitUps.contains(peer) ? streaks.get(peer) + 1 : 0);
            }
            if (limitUps.contains(symbol)) {
                closedLimitUps++;
                int candidateHeight = streaks.getOrDefault(symbol, 0);
                candidateMaxBoard = Math.max(candidateMaxBoard, candidateHeight);
                long higher = streaks.values().stream().filter(height -> height > candidateHeight).count();
                if (higher < 2) {
                    success = true;
                }
            }
        }
        List<BigDecimal> candidateCloses = sessions.stream()
                .map(date -> closes.get(date).get(symbol)).toList();
        return ready(
                pending, success,
                cumulativeReturn(pending.sampleClose(), candidateCloses.get(0)),
                cumulativeReturn(pending.sampleClose(), candidateCloses.get(2)), null,
                maxDrawdown(pending.sampleClose(), candidateCloses), closedLimitUps,
                candidateMaxBoard, sessions.get(2));
    }

    private LeaderSnapshotStore.OutcomeResult ready(
            LeaderSnapshotStore.PendingOutcome pending,
            boolean success,
            BigDecimal returnT1,
            BigDecimal returnT3,
            BigDecimal returnT5,
            BigDecimal maxDrawdown,
            Integer closedLimitUps,
            Integer maxBoard,
            LocalDate evaluatedThrough
    ) {
        return new LeaderSnapshotStore.OutcomeResult(
                pending.snapshotId(), pending.lane(), pending.symbol(), "READY", success,
                scale(returnT1), scale(returnT3), scale(returnT5), scale(maxDrawdown),
                closedLimitUps, maxBoard, evaluatedThrough);
    }

    private LeaderSnapshotStore.OutcomeResult pending(
            LeaderSnapshotStore.PendingOutcome pending,
            LocalDate evaluatedThrough
    ) {
        return new LeaderSnapshotStore.OutcomeResult(
                pending.snapshotId(), pending.lane(), pending.symbol(), "PENDING_DATA", null,
                null, null, null, null, null, null, evaluatedThrough);
    }

    private boolean ready(LocalDate date) {
        return dailyBars.marketDay(date)
                .filter(day -> day.status() == FirstBoardMarketDayStatus.READY)
                .filter(day -> day.limitUpPoolComplete() && day.failedSymbolCount() == 0)
                .isPresent();
    }

    private boolean hasCatchUpLookahead(PotentialLeaderSnapshot snapshot) {
        return combinedCandidates(snapshot).stream()
                .map(PotentialLeaderSnapshot.Candidate::dragonTiger)
                .filter(value -> value != null && value.disclosureDate() != null)
                .anyMatch(value -> !value.disclosureDate().isBefore(snapshot.tradeDate()));
    }

    private List<PotentialLeaderSnapshot.Candidate> combinedCandidates(PotentialLeaderSnapshot snapshot) {
        List<PotentialLeaderSnapshot.Candidate> candidates = new ArrayList<>();
        candidates.addAll(snapshot.potentialCandidates());
        candidates.addAll(snapshot.competitionCandidates());
        return candidates;
    }

    private Map<String, BigDecimal> normalizedPrices(Map<String, BigDecimal> source) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((symbol, close) ->
                    result.put(LeaderDirectionEngine.normalizeSymbol(symbol), close));
        }
        return result;
    }

    private boolean completePrices(Collection<String> symbols, Map<String, BigDecimal> prices) {
        return symbols.stream().allMatch(symbol -> positive(prices.get(symbol)));
    }

    private Set<String> normalizedSymbols(Set<String> source) {
        if (source == null) {
            return Set.of();
        }
        return source.stream().map(LeaderDirectionEngine::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    private BigDecimal cumulativeReturn(BigDecimal base, BigDecimal close) {
        if (!positive(base) || close == null) {
            return BigDecimal.ZERO;
        }
        return close.subtract(base).multiply(new BigDecimal("100"))
                .divide(base, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal maxDrawdown(BigDecimal sampleClose, List<BigDecimal> closes) {
        BigDecimal peak = sampleClose;
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal close : closes) {
            peak = peak.max(close);
            BigDecimal drawdown = close.subtract(peak).multiply(new BigDecimal("100"))
                    .divide(peak, 8, RoundingMode.HALF_UP);
            worst = worst.min(drawdown);
        }
        return worst;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(double value) {
        return BigDecimal.valueOf(value * 100D).setScale(2, RoundingMode.HALF_UP);
    }
}
