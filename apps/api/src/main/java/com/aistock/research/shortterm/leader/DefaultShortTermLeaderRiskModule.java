package com.aistock.research.shortterm.leader;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermHotDirection;
import com.aistock.research.shortterm.leader.ShortTermLeaderRisk.BaselineType;
import com.aistock.research.shortterm.leader.ShortTermLeaderRiskSignal.MovementState;
import com.aistock.research.shortterm.leader.ShortTermLeaderSnapshot.DirectionObservation;
import com.aistock.research.shortterm.leader.ShortTermLeaderSnapshot.LeaderObservation;
import com.aistock.research.shortterm.leader.ShortTermLeaderSnapshot.WeightObservation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DefaultShortTermLeaderRiskModule implements ShortTermLeaderRiskModule {

    static final String RULE_VERSION = "short-term-leader-risk-v2-scheduled-day";
    private static final BigDecimal WEIGHT_MARKET_CAP_COHORT = new BigDecimal("0.05");
    private static final BigDecimal WEIGHT_ACTIVE_AMOUNT_COHORT = new BigDecimal("0.10");
    private static final BigDecimal WEIGHT_MIN_RISE = new BigDecimal("2.00");
    private static final BigDecimal WEIGHT_MIN_ACCELERATION = new BigDecimal("1.00");
    private static final int WEIGHT_MIN_RANK_IMPROVEMENT = 20;
    private static final BigDecimal THEME_ACTIVE_AMOUNT_COHORT = new BigDecimal("0.15");
    private static final BigDecimal THEME_MIN_RISE = new BigDecimal("4.00");
    private static final BigDecimal THEME_MIN_ACCELERATION = new BigDecimal("1.50");
    private static final int THEME_MIN_DIRECTION_RANK_IMPROVEMENT = 2;
    private static final BigDecimal CONCENTRATED_CANDIDATE_PERCENT = new BigDecimal("50.00");
    private static final int MAX_SIGNALS = 3;

    private static final BigDecimal MIN_RELIABLE_COVERAGE = new BigDecimal("0.95");
    private static final Pattern LEADER_SYMBOL = Pattern.compile("\\((\\d{6})\\)\\s*$");

    private final ShortTermLeaderSnapshotStore snapshotStore;

    public DefaultShortTermLeaderRiskModule(ShortTermLeaderSnapshotStore snapshotStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    }

    @Override
    public ShortTermLeaderRisk evaluate(ShortTermLeaderRiskInput input) {
        if (!reliable(input)) return unavailableCoverage(input);
        ShortTermLeaderSnapshot current = snapshot(input);
        ShortTermLeaderRisk currentRisk = currentRisk(current, input.candidateIndustries(), false);
        return aggregateIntraday(current, input.candidateIndustries(), currentRisk);
    }

    @Override
    public ShortTermLeaderRisk captureCheckpoint(ShortTermLeaderRiskInput input) {
        if (!reliable(input)) return unavailableCoverage(input);
        ShortTermLeaderSnapshot current = snapshot(input);
        ShortTermLeaderRisk risk = currentRisk(current, List.of(), true);
        snapshotStore.saveCheckpoint(current, risk);
        return risk;
    }

    private ShortTermLeaderRisk currentRisk(
            ShortTermLeaderSnapshot current,
            List<String> candidateIndustries,
            boolean checkpointCapture
    ) {
        Baseline baseline = baseline(current);
        return baseline.snapshot() == null
                ? baselineBuilding(current, candidateIndustries, checkpointCapture)
                : compare(current, baseline, candidateIndustries);
    }

    private boolean reliable(ShortTermLeaderRiskInput input) {
        if (input == null || input.tradeDate() == null || input.capturedAt() == null) {
            return false;
        }
        ShortTermCoverageSnapshot coverage = input.coverage();
        return coverage != null
                && coverage.executionReliable()
                && coverage.coverageRatio() != null
                && coverage.coverageRatio().compareTo(MIN_RELIABLE_COVERAGE) >= 0;
    }

    private ShortTermLeaderRisk unavailableCoverage(ShortTermLeaderRiskInput input) {
        Instant evaluatedAt = input == null || input.capturedAt() == null
                ? Instant.now()
                : input.capturedAt();
        String reason = "全市场有效行情覆盖未通过95%，龙头异动风险不可用";
        return new ShortTermLeaderRisk(
                RULE_VERSION,
                ShortTermLeaderRisk.Status.UNAVAILABLE,
                BaselineType.INITIAL,
                null,
                List.of(),
                null,
                BigDecimal.ZERO,
                false,
                reason,
                "未保存本次快照，避免不完整行情污染后续基线。",
                List.of(reason),
                true,
                evaluatedAt
        );
    }

    private ShortTermLeaderSnapshot snapshot(ShortTermLeaderRiskInput input) {
        List<EastMoneyQuote> quotes = uniqueQuotes(input.quotes());
        Map<String, EastMoneyQuote> quoteBySymbol = quotes.stream()
                .collect(Collectors.toMap(
                        EastMoneyQuote::symbol,
                        quote -> quote,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        AmountMetrics amountMetrics = amountMetrics(quotes);
        List<WeightObservation> weights = weightObservations(quotes, amountMetrics);
        List<DirectionObservation> directions = directionObservations(
                input.hotDirections(), quoteBySymbol, amountMetrics
        );
        String snapshotId = UUID.randomUUID().toString();
        return new ShortTermLeaderSnapshot(
                RULE_VERSION,
                snapshotId,
                input.tradeDate(),
                input.capturedAt(),
                quotes.size(),
                weights,
                directions
        );
    }

    private List<EastMoneyQuote> uniqueQuotes(List<EastMoneyQuote> source) {
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        for (EastMoneyQuote quote : source) {
            if (quote == null || quote.symbol() == null || quote.symbol().isBlank()) {
                continue;
            }
            unique.putIfAbsent(quote.symbol().trim(), quote);
        }
        return List.copyOf(unique.values());
    }

    private AmountMetrics amountMetrics(List<EastMoneyQuote> quotes) {
        List<EastMoneyQuote> ranked = quotes.stream()
                .filter(quote -> positive(quote.amount()))
                .sorted(Comparator.comparing(EastMoneyQuote::amount).reversed()
                        .thenComparing(EastMoneyQuote::symbol))
                .toList();
        BigDecimal total = ranked.stream()
                .map(EastMoneyQuote::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Integer> ranks = new LinkedHashMap<>();
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            EastMoneyQuote quote = ranked.get(index);
            ranks.put(quote.symbol(), index + 1);
            shares.put(quote.symbol(), percent(quote.amount(), total));
        }
        return new AmountMetrics(ranks, shares);
    }

    private List<WeightObservation> weightObservations(
            List<EastMoneyQuote> quotes,
            AmountMetrics amountMetrics
    ) {
        List<EastMoneyQuote> marketCapRanked = quotes.stream()
                .filter(quote -> positive(quote.totalMarketValue()))
                .sorted(Comparator.comparing(EastMoneyQuote::totalMarketValue).reversed()
                        .thenComparing(EastMoneyQuote::symbol))
                .toList();
        int cohortSize = Math.min(
                50,
                fractionCohortSize(marketCapRanked.size(), WEIGHT_MARKET_CAP_COHORT, 1)
        );
        return marketCapRanked.stream()
                .limit(cohortSize)
                .map(quote -> new WeightObservation(
                        quote.symbol(),
                        text(quote.name()),
                        text(quote.industry()),
                        scale(quote.changePercent()),
                        amountMetrics.ranks().get(quote.symbol()),
                        amountMetrics.shares().get(quote.symbol()),
                        quote.totalMarketValue()
                ))
                .toList();
    }

    private List<DirectionObservation> directionObservations(
            List<ShortTermHotDirection> source,
            Map<String, EastMoneyQuote> quoteBySymbol,
            AmountMetrics amountMetrics
    ) {
        List<ShortTermHotDirection> ranked = source.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                                (ShortTermHotDirection direction) -> value(direction.heatScore()),
                                Comparator.reverseOrder())
                        .thenComparing(direction -> value(direction.totalAmount()), Comparator.reverseOrder())
                        .thenComparing(direction -> text(direction.code())))
                .limit(8)
                .toList();
        List<DirectionObservation> observations = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            ShortTermHotDirection direction = ranked.get(index);
            List<LeaderObservation> leaders = directionLeaders(direction, quoteBySymbol, amountMetrics);
            observations.add(new DirectionObservation(
                    text(direction.code()),
                    text(direction.label()),
                    scale(direction.heatScore()),
                    index + 1,
                    topLeaderResolved(direction, quoteBySymbol),
                    leaders
            ));
        }
        return List.copyOf(observations);
    }

    private List<LeaderObservation> directionLeaders(
            ShortTermHotDirection direction,
            Map<String, EastMoneyQuote> quoteBySymbol,
            AmountMetrics amountMetrics
    ) {
        List<String> encodedLeaders = direction.leaders() == null ? List.of() : direction.leaders();
        Map<String, LeaderObservation> leaders = new LinkedHashMap<>();
        for (String encoded : encodedLeaders) {
            Matcher matcher = LEADER_SYMBOL.matcher(text(encoded));
            if (!matcher.find()) {
                continue;
            }
            EastMoneyQuote quote = quoteBySymbol.get(matcher.group(1));
            if (quote == null) {
                continue;
            }
            leaders.putIfAbsent(quote.symbol(), new LeaderObservation(
                    quote.symbol(),
                    text(quote.name()),
                    text(quote.industry()),
                    scale(quote.changePercent()),
                    amountMetrics.ranks().get(quote.symbol()),
                    amountMetrics.shares().get(quote.symbol()),
                    quote.totalMarketValue()
            ));
            if (leaders.size() == 3) {
                break;
            }
        }
        return List.copyOf(leaders.values());
    }

    private boolean topLeaderResolved(
            ShortTermHotDirection direction,
            Map<String, EastMoneyQuote> quoteBySymbol
    ) {
        if (direction.leaders() == null || direction.leaders().isEmpty()) {
            return false;
        }
        Matcher matcher = LEADER_SYMBOL.matcher(text(direction.leaders().get(0)));
        return matcher.find() && quoteBySymbol.containsKey(matcher.group(1));
    }

    private Baseline baseline(ShortTermLeaderSnapshot current) {
        return snapshotStore.latestSameDayBefore(
                        RULE_VERSION, current.tradeDate(), current.capturedAt()
                )
                .map(snapshot -> new Baseline(BaselineType.PREVIOUS_SCAN, snapshot))
                .orElseGet(() -> snapshotStore.latestBeforeTradeDate(RULE_VERSION, current.tradeDate())
                        .map(snapshot -> new Baseline(BaselineType.PREVIOUS_TRADING_DAY, snapshot))
                        .orElseGet(() -> new Baseline(BaselineType.INITIAL, null)));
    }

    private ShortTermLeaderRisk baselineBuilding(
            ShortTermLeaderSnapshot current,
            List<String> candidateIndustries,
            boolean checkpointCapture
    ) {
        CandidateConcentration concentration = candidateConcentration(candidateIndustries);
        return new ShortTermLeaderRisk(
                RULE_VERSION,
                ShortTermLeaderRisk.Status.BASELINE_BUILDING,
                BaselineType.INITIAL,
                null,
                List.of(),
                concentration.industry(),
                concentration.percent(),
                false,
                checkpointCapture
                        ? "首个后台观察点已保存，暂时还不能判断龙头异动。"
                        : "后台基线尚未建立，本次暂不能判断异动，不代表没有风险。",
                checkpointCapture
                        ? "下一次后台观察将从本次全市场排名开始比较。"
                        : "后台会在09:50、11:30、14:40自动建立观察点，无需重复点击扫描。",
                dataGaps(current, null),
                true,
                current.capturedAt()
        );
    }

    private ShortTermLeaderRisk compare(
            ShortTermLeaderSnapshot current,
            Baseline baseline,
            List<String> candidateIndustries
    ) {
        List<ShortTermLeaderRiskSignal> signals = new ArrayList<>();
        signals.addAll(weightSignals(current, baseline.snapshot()));
        signals.addAll(themeSignals(current, baseline.snapshot()));
        signals = signals.stream()
                .map(signal -> withMovement(
                        signal, current.capturedAt(), MovementState.DETECTED, signal.reason()))
                .toList();
        List<ShortTermLeaderRiskSignal> strongest = signals.stream()
                .sorted(signalComparator())
                .limit(MAX_SIGNALS)
                .toList();

        CandidateConcentration concentration = candidateConcentration(candidateIndustries);
        boolean directionConflict = concentration.percent().compareTo(CONCENTRATED_CANDIDATE_PERCENT) >= 0
                && !signals.isEmpty()
                && signals.stream().allMatch(signal -> !signal.direction().isBlank()
                && !sameDirection(concentration.industry(), signal.direction()));
        ShortTermLeaderRisk.Status status = strongest.isEmpty()
                ? ShortTermLeaderRisk.Status.CLEAR
                : ShortTermLeaderRisk.Status.WARNING;
        String summary;
        if (strongest.isEmpty()) {
            summary = "暂未发现明显龙头异动。";
        } else if (directionConflict) {
            summary = "发现资金切换风险：候选方向与新异动龙头不同。";
        } else {
            summary = "发现" + strongest.size() + "条龙头强化信号，需留意资金切换。";
        }
        return new ShortTermLeaderRisk(
                RULE_VERSION,
                status,
                baseline.type(),
                baseline.snapshot().capturedAt(),
                strongest,
                concentration.industry(),
                concentration.percent(),
                directionConflict,
                summary,
                "比较后台观察点之间的涨幅、全市场成交额排名和占比，不直接比较跨时点累计成交额。",
                dataGaps(current, baseline.snapshot()),
                true,
                current.capturedAt()
        );
    }

    private ShortTermLeaderRisk aggregateIntraday(
            ShortTermLeaderSnapshot current,
            List<String> candidateIndustries,
            ShortTermLeaderRisk currentRisk
    ) {
        List<ShortTermLeaderCheckpoint> checkpoints = snapshotStore.sameDayCheckpointsBefore(
                RULE_VERSION, current.tradeDate(), current.capturedAt());
        if (checkpoints.isEmpty()) {
            return currentRisk;
        }

        Map<String, ShortTermLeaderRiskSignal> merged = new LinkedHashMap<>();
        for (ShortTermLeaderCheckpoint checkpoint : checkpoints) {
            if (checkpoint == null || checkpoint.snapshot() == null
                    || checkpoint.risk() == null
                    || checkpoint.risk().status() != ShortTermLeaderRisk.Status.WARNING) {
                continue;
            }
            for (ShortTermLeaderRiskSignal signal : checkpoint.risk().signals()) {
                Instant detectedAt = signal.detectedAt() == null
                        ? checkpoint.snapshot().capturedAt()
                        : signal.detectedAt();
                ShortTermLeaderRiskSignal refreshed = refreshHistoricalSignal(
                        current, signal, detectedAt);
                merged.putIfAbsent(signalKey(signal), refreshed);
            }
        }

        for (ShortTermLeaderRiskSignal signal : currentRisk.signals()) {
            String key = signalKey(signal);
            ShortTermLeaderRiskSignal previous = merged.get(key);
            Instant detectedAt = previous == null || previous.detectedAt() == null
                    ? current.capturedAt()
                    : previous.detectedAt();
            MovementState movementState = previous == null
                    ? MovementState.DETECTED
                    : MovementState.ONGOING;
            merged.put(key, withMovement(signal, detectedAt, movementState, signal.reason()));
        }

        if (merged.isEmpty()) {
            return currentRisk;
        }

        List<ShortTermLeaderRiskSignal> allSignals = List.copyOf(merged.values());
        List<ShortTermLeaderRiskSignal> activeSignals = allSignals.stream()
                .filter(signal -> signal.movementState() != MovementState.RECEDED)
                .toList();
        List<ShortTermLeaderRiskSignal> strongest = allSignals.stream()
                .sorted(Comparator.comparingInt(this::movementPriority)
                        .thenComparing(signalComparator()))
                .limit(MAX_SIGNALS)
                .toList();

        CandidateConcentration concentration = candidateConcentration(candidateIndustries);
        boolean directionConflict = concentration.percent().compareTo(CONCENTRATED_CANDIDATE_PERCENT) >= 0
                && !activeSignals.isEmpty()
                && activeSignals.stream().allMatch(signal -> !signal.direction().isBlank()
                && !sameDirection(concentration.industry(), signal.direction()));
        boolean activeRisk = !activeSignals.isEmpty();
        String summary;
        if (!activeRisk) {
            summary = "今日曾出现龙头异动，目前已经回落，暂未发现新的强化。";
        } else if (directionConflict) {
            summary = "发现资金切换风险：今日异动龙头仍在强化，且与当前候选方向不同。";
        } else if (activeSignals.stream().anyMatch(
                signal -> signal.movementState() == MovementState.DETECTED)) {
            summary = "发现新的龙头强化信号，需留意资金切换。";
        } else {
            summary = "今日早些时候发现的龙头异动仍在强化。";
        }

        return new ShortTermLeaderRisk(
                RULE_VERSION,
                activeRisk ? ShortTermLeaderRisk.Status.WARNING : ShortTermLeaderRisk.Status.CLEAR,
                currentRisk.baselineType(),
                currentRisk.baselineAt(),
                strongest,
                concentration.industry(),
                concentration.percent(),
                directionConflict,
                summary,
                "后台在09:50、11:30、14:40保存观察点；本次合并当天已出现的信号并判断当前是否仍然强势。",
                currentRisk.dataGaps(),
                true,
                current.capturedAt()
        );
    }

    private ShortTermLeaderRiskSignal refreshHistoricalSignal(
            ShortTermLeaderSnapshot current,
            ShortTermLeaderRiskSignal signal,
            Instant detectedAt
    ) {
        if (signal.track() == ShortTermLeaderRiskSignal.Track.WEIGHT) {
            WeightObservation observation = current.weights().stream()
                    .filter(value -> value.symbol().equals(signal.symbol()))
                    .findFirst()
                    .orElse(null);
            int activeRankLimit = fractionCohortSize(
                    current.quoteCount(), WEIGHT_ACTIVE_AMOUNT_COHORT, 20);
            boolean active = observation != null
                    && atLeast(observation.changePercent(), WEIGHT_MIN_RISE)
                    && observation.amountRank() != null
                    && observation.amountRank() <= activeRankLimit;
            if (!active) {
                return withMovement(signal, detectedAt, MovementState.RECEDED, signal.reason());
            }
            return new ShortTermLeaderRiskSignal(
                    signal.track(),
                    observation.symbol(),
                    observation.name(),
                    observation.industry(),
                    observation.changePercent(),
                    signal.baselineChangePercent(),
                    difference(observation.changePercent(), signal.baselineChangePercent()),
                    observation.amountRank(),
                    signal.baselineAmountRank(),
                    observation.amountSharePercent(),
                    observation.totalMarketValue(),
                    signal.reason(),
                    detectedAt,
                    MovementState.ONGOING
            );
        }

        DirectionObservation direction = current.directions().stream()
                .filter(value -> sameDirection(value.label(), signal.direction()))
                .findFirst()
                .orElse(null);
        LeaderObservation leader = findLeader(direction, signal.symbol());
        int activeRankLimit = fractionCohortSize(
                current.quoteCount(), THEME_ACTIVE_AMOUNT_COHORT, 30);
        boolean active = leader != null
                && atLeast(leader.changePercent(), THEME_MIN_RISE)
                && leader.amountRank() != null
                && leader.amountRank() <= activeRankLimit;
        if (!active) {
            return withMovement(signal, detectedAt, MovementState.RECEDED, signal.reason());
        }
        return new ShortTermLeaderRiskSignal(
                signal.track(),
                leader.symbol(),
                leader.name(),
                direction.label(),
                leader.changePercent(),
                signal.baselineChangePercent(),
                difference(leader.changePercent(), signal.baselineChangePercent()),
                leader.amountRank(),
                signal.baselineAmountRank(),
                leader.amountSharePercent(),
                leader.totalMarketValue(),
                signal.reason(),
                detectedAt,
                MovementState.ONGOING
        );
    }

    private ShortTermLeaderRiskSignal withMovement(
            ShortTermLeaderRiskSignal signal,
            Instant detectedAt,
            MovementState movementState,
            String reason
    ) {
        return new ShortTermLeaderRiskSignal(
                signal.track(),
                signal.symbol(),
                signal.name(),
                signal.direction(),
                signal.currentChangePercent(),
                signal.baselineChangePercent(),
                signal.changeDeltaPercentPoints(),
                signal.currentAmountRank(),
                signal.baselineAmountRank(),
                signal.amountSharePercent(),
                signal.totalMarketValue(),
                reason,
                detectedAt,
                movementState
        );
    }

    private String signalKey(ShortTermLeaderRiskSignal signal) {
        return signal.track() + "|" + signal.symbol() + "|" + normalizeDirection(signal.direction());
    }

    private int movementPriority(ShortTermLeaderRiskSignal signal) {
        return switch (signal.movementState()) {
            case DETECTED -> 0;
            case ONGOING -> 1;
            case RECEDED -> 2;
        };
    }

    private List<ShortTermLeaderRiskSignal> weightSignals(
            ShortTermLeaderSnapshot current,
            ShortTermLeaderSnapshot baseline
    ) {
        int activeRankLimit = fractionCohortSize(
                current.quoteCount(), WEIGHT_ACTIVE_AMOUNT_COHORT, 20
        );
        Map<String, WeightObservation> baselineBySymbol = baseline.weights().stream()
                .collect(Collectors.toMap(WeightObservation::symbol, value -> value, (first, ignored) -> first));
        Map<String, ShortTermLeaderRiskSignal> signals = new LinkedHashMap<>();
        for (WeightObservation observation : current.weights()) {
            if (!atLeast(observation.changePercent(), WEIGHT_MIN_RISE)
                    || observation.amountRank() == null
                    || observation.amountRank() > activeRankLimit) {
                continue;
            }
            WeightObservation previous = baselineBySymbol.get(observation.symbol());
            BigDecimal delta = previous == null
                    ? null
                    : difference(observation.changePercent(), previous.changePercent());
            boolean newlyEntered = previous == null;
            boolean accelerated = atLeast(delta, WEIGHT_MIN_ACCELERATION);
            boolean rankImproved = rankImprovement(previous == null ? null : previous.amountRank(),
                    observation.amountRank()) >= WEIGHT_MIN_RANK_IMPROVEMENT;
            if (!newlyEntered && !accelerated && !rankImproved) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (newlyEntered) reasons.add("新进入权重观察池");
            if (accelerated) reasons.add("涨幅较基准加速至少1.00个百分点");
            if (rankImproved) reasons.add("成交额排名较基准提升至少20位");
            signals.putIfAbsent(observation.symbol(), new ShortTermLeaderRiskSignal(
                    ShortTermLeaderRiskSignal.Track.WEIGHT,
                    observation.symbol(),
                    observation.name(),
                    observation.industry(),
                    observation.changePercent(),
                    previous == null ? null : previous.changePercent(),
                    delta,
                    observation.amountRank(),
                    previous == null ? null : previous.amountRank(),
                    observation.amountSharePercent(),
                    observation.totalMarketValue(),
                    String.join("；", reasons)
            ));
        }
        return List.copyOf(signals.values());
    }

    private List<ShortTermLeaderRiskSignal> themeSignals(
            ShortTermLeaderSnapshot current,
            ShortTermLeaderSnapshot baseline
    ) {
        int activeRankLimit = fractionCohortSize(
                current.quoteCount(), THEME_ACTIVE_AMOUNT_COHORT, 30
        );
        Map<String, DirectionObservation> baselineByCode = baseline.directions().stream()
                .collect(Collectors.toMap(DirectionObservation::code, value -> value, (first, ignored) -> first));
        Map<String, ShortTermLeaderRiskSignal> signals = new LinkedHashMap<>();
        for (DirectionObservation direction : current.directions()) {
            DirectionObservation previousDirection = baselineByCode.get(direction.code());
            boolean directionEntered = previousDirection == null;
            boolean directionRankImproved = previousDirection != null
                    && previousDirection.rank() - direction.rank() >= THEME_MIN_DIRECTION_RANK_IMPROVEMENT;
            String previousTopSymbol = firstLeaderSymbol(previousDirection);
            String currentTopSymbol = firstLeaderSymbol(direction);
            boolean leaderChanged = previousDirection != null
                    && previousDirection.topLeaderResolved()
                    && direction.topLeaderResolved()
                    && !previousTopSymbol.isBlank()
                    && !currentTopSymbol.isBlank()
                    && !currentTopSymbol.equals(previousTopSymbol);
            for (LeaderObservation leader : direction.leaders()) {
                if (!atLeast(leader.changePercent(), THEME_MIN_RISE)
                        || leader.amountRank() == null
                        || leader.amountRank() > activeRankLimit) {
                    continue;
                }
                LeaderObservation previousLeader = findLeader(previousDirection, leader.symbol());
                BigDecimal delta = previousLeader == null
                        ? null
                        : difference(leader.changePercent(), previousLeader.changePercent());
                boolean accelerated = atLeast(delta, THEME_MIN_ACCELERATION);
                boolean changedTopLeader = leaderChanged && leader.symbol().equals(currentTopSymbol);
                if (!directionEntered && !directionRankImproved && !changedTopLeader && !accelerated) {
                    continue;
                }
                List<String> reasons = new ArrayList<>();
                if (directionEntered) reasons.add("方向新进入前八");
                if (directionRankImproved) reasons.add("方向排名较基准提升至少2位");
                if (changedTopLeader) reasons.add("方向龙头更替并达到强势门槛");
                if (accelerated) reasons.add("龙头涨幅较基准加速至少1.50个百分点");
                signals.putIfAbsent(leader.symbol(), new ShortTermLeaderRiskSignal(
                        ShortTermLeaderRiskSignal.Track.THEME,
                        leader.symbol(),
                        leader.name(),
                        direction.label(),
                        leader.changePercent(),
                        previousLeader == null ? null : previousLeader.changePercent(),
                        delta,
                        leader.amountRank(),
                        previousLeader == null ? null : previousLeader.amountRank(),
                        leader.amountSharePercent(),
                        leader.totalMarketValue(),
                        String.join("；", reasons)
                ));
            }
        }
        return List.copyOf(signals.values());
    }

    private Comparator<ShortTermLeaderRiskSignal> signalComparator() {
        return Comparator.comparing(
                        (ShortTermLeaderRiskSignal signal) -> signal.changeDeltaPercentPoints() == null
                                ? new BigDecimal("-999")
                                : signal.changeDeltaPercentPoints(),
                        Comparator.reverseOrder())
                .thenComparing(ShortTermLeaderRiskSignal::currentChangePercent, Comparator.reverseOrder())
                .thenComparing(signal -> signal.currentAmountRank() == null
                        ? Integer.MAX_VALUE
                        : signal.currentAmountRank())
                .thenComparing(ShortTermLeaderRiskSignal::symbol);
    }

    private CandidateConcentration candidateConcentration(List<String> source) {
        Map<String, IndustryCount> counts = new LinkedHashMap<>();
        int total = 0;
        for (String industry : source) {
            String display = text(industry);
            String key = normalizeDirection(display);
            if (key.isBlank()) {
                continue;
            }
            total++;
            counts.computeIfAbsent(key, ignored -> new IndustryCount(display)).increment();
        }
        if (total == 0) {
            return new CandidateConcentration(null, BigDecimal.ZERO);
        }
        IndustryCount dominant = null;
        for (IndustryCount candidate : counts.values()) {
            if (dominant == null || candidate.count() > dominant.count()) {
                dominant = candidate;
            }
        }
        return new CandidateConcentration(
                dominant.industry(),
                percent(BigDecimal.valueOf(dominant.count()), BigDecimal.valueOf(total))
        );
    }

    private List<String> dataGaps(
            ShortTermLeaderSnapshot current,
            ShortTermLeaderSnapshot baseline
    ) {
        List<String> gaps = new ArrayList<>();
        if (current.weights().isEmpty()) {
            gaps.add("总市值字段缺失，权重龙头轨道不可用");
        }
        if (current.directions().isEmpty()) {
            gaps.add("热门方向缺失，题材龙头轨道不可用");
        }
        if (hasUnresolvedDirectionLeader(current)) {
            gaps.add("当前热门方向首位龙头未解析，龙头证据不足，相关方向无法确认龙头变化");
        }
        if (baseline != null && hasUnresolvedDirectionLeader(baseline)) {
            gaps.add("基准热门方向首位龙头未解析，龙头证据不足，相关方向无法确认龙头更替");
        }
        return List.copyOf(gaps);
    }

    private boolean hasUnresolvedDirectionLeader(ShortTermLeaderSnapshot snapshot) {
        return snapshot.directions().stream()
                .anyMatch(direction -> !direction.topLeaderResolved());
    }

    private LeaderObservation findLeader(DirectionObservation direction, String symbol) {
        if (direction == null) {
            return null;
        }
        return direction.leaders().stream()
                .filter(leader -> leader.symbol().equals(symbol))
                .findFirst()
                .orElse(null);
    }

    private String firstLeaderSymbol(DirectionObservation direction) {
        if (direction == null || direction.leaders().isEmpty()) {
            return "";
        }
        return direction.leaders().get(0).symbol();
    }

    private boolean sameDirection(String left, String right) {
        String normalizedLeft = normalizeDirection(left);
        String normalizedRight = normalizeDirection(right);
        return !normalizedLeft.isBlank()
                && !normalizedRight.isBlank()
                && (normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft));
    }

    private String normalizeDirection(String value) {
        return text(value).replaceAll("\\s+", "").toUpperCase();
    }

    private int rankImprovement(Integer baselineRank, Integer currentRank) {
        return baselineRank == null || currentRank == null ? 0 : baselineRank - currentRank;
    }

    private int fractionCohortSize(int total, BigDecimal fraction, int minimum) {
        if (total <= 0) {
            return 0;
        }
        int fractionSize = fraction.multiply(BigDecimal.valueOf(total))
                .setScale(0, RoundingMode.CEILING)
                .intValue();
        return Math.min(total, Math.max(minimum, fractionSize));
    }

    private boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal difference(BigDecimal current, BigDecimal baseline) {
        return current == null || baseline == null ? null : scale(current.subtract(baseline));
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record AmountMetrics(
            Map<String, Integer> ranks,
            Map<String, BigDecimal> shares
    ) {
    }

    private record Baseline(BaselineType type, ShortTermLeaderSnapshot snapshot) {
    }

    private record CandidateConcentration(String industry, BigDecimal percent) {
    }

    private static final class IndustryCount {

        private final String industry;
        private int count;

        private IndustryCount(String industry) {
            this.industry = industry;
        }

        private void increment() {
            count++;
        }

        private String industry() {
            return industry;
        }

        private int count() {
            return count;
        }
    }
}
