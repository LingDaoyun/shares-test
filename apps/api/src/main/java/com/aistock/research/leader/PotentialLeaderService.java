package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardAlertStatus;
import com.aistock.research.firstboard.FirstBoardBaselineService;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineAccess;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermLimitUpBoardService;
import com.aistock.research.shortterm.ShortTermLimitUpBoardSnapshot;
import com.aistock.research.shortterm.ShortTermLimitUpSentiment;
import com.aistock.research.trading.TradingClockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PotentialLeaderService {

    private static final int FULL_MARKET_LIMIT = 6000;
    private static final long CACHE_SECONDS = 60;

    private final EastMoneyClient eastMoneyClient;
    private final FirstBoardBaselineService baselineService;
    private final ShortTermLimitUpBoardService boardService;
    private final TradingClockService tradingClock;
    private final LeaderDirectionEngine directionEngine;
    private final LeaderCandidateEngine candidateEngine;
    private final LeaderScoreEngine scoreEngine;
    private final DragonTigerGateway dragonTigerGateway;
    private final LeaderLearningService learningService;
    private final Clock cacheClock;
    private final Map<CacheKey, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, PotentialLeaderSnapshot> lastSuccessful = new ConcurrentHashMap<>();
    private final Map<CacheKey, Object> refreshLocks = new ConcurrentHashMap<>();

    @Autowired
    public PotentialLeaderService(
            EastMoneyClient eastMoneyClient,
            FirstBoardBaselineService baselineService,
            ShortTermLimitUpBoardService boardService,
            TradingClockService tradingClock,
            LeaderDirectionEngine directionEngine,
            LeaderCandidateEngine candidateEngine,
            LeaderScoreEngine scoreEngine,
            DragonTigerGateway dragonTigerGateway,
            LeaderLearningService learningService
    ) {
        this(eastMoneyClient, baselineService, boardService, tradingClock, directionEngine,
                candidateEngine, scoreEngine, dragonTigerGateway, learningService, Clock.systemUTC());
    }

    PotentialLeaderService(
            EastMoneyClient eastMoneyClient,
            FirstBoardBaselineService baselineService,
            ShortTermLimitUpBoardService boardService,
            TradingClockService tradingClock,
            LeaderDirectionEngine directionEngine,
            LeaderCandidateEngine candidateEngine,
            LeaderScoreEngine scoreEngine,
            DragonTigerGateway dragonTigerGateway,
            LeaderLearningService learningService,
            Clock cacheClock
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.baselineService = baselineService;
        this.boardService = boardService;
        this.tradingClock = tradingClock;
        this.directionEngine = directionEngine;
        this.candidateEngine = candidateEngine;
        this.scoreEngine = scoreEngine;
        this.dragonTigerGateway = dragonTigerGateway;
        this.learningService = learningService;
        this.cacheClock = cacheClock;
    }

    public PotentialLeaderSnapshot snapshot(boolean includeGrowth, boolean includeStar) {
        LocalDate tradeDate = effectiveTradeDate();
        CacheKey key = new CacheKey(tradeDate, includeGrowth, includeStar);
        Instant now = cacheClock.instant();
        CachedSnapshot cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.snapshot();
        }
        Object refreshLock = refreshLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (refreshLock) {
            now = cacheClock.instant();
            cached = cache.get(key);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.snapshot();
            }
            PotentialLeaderSnapshot refreshed = refresh(key, now);
            cache.put(key, new CachedSnapshot(refreshed, now.plusSeconds(CACHE_SECONDS)));
            cache.keySet().removeIf(existing -> existing.tradeDate().isBefore(tradeDate.minusDays(7)));
            lastSuccessful.keySet().removeIf(existing -> existing.tradeDate().isBefore(tradeDate.minusDays(7)));
            return refreshed;
        }
    }

    public Optional<LeaderBarSeries> bars(String symbol) {
        String normalized = LeaderDirectionEngine.normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        LocalDate tradeDate = effectiveTradeDate();
        FirstBoardLeaderBaselineAccess baseline = baselineService.leaderAccess(tradeDate);
        if (baseline.status() != FirstBoardAlertStatus.READY) {
            return Optional.empty();
        }
        return baselineService.recentBars(tradeDate, normalized)
                .map(rows -> new LeaderBarSeries(
                        normalized, tradeDate, baseline.baselineDate(), "QFQ", rows));
    }

    public LocalDate currentTradeDate() {
        return effectiveTradeDate();
    }

    public PotentialLeaderSnapshot.Candidate currentCandidate(
            PotentialLeaderSnapshot.Lane lane,
            String symbol,
            String expectedHash
    ) {
        String normalized = LeaderDirectionEngine.normalizeSymbol(symbol);
        LocalDate currentDate = tradingClock.currentMarketDate();
        Instant now = cacheClock.instant();
        List<PotentialLeaderSnapshot.Candidate> matchingSymbols = cache.values().stream()
                .filter(cached -> now.isBefore(cached.expiresAt()))
                .map(CachedSnapshot::snapshot)
                .filter(snapshot -> snapshot.status() == PotentialLeaderSnapshot.Status.READY)
                .filter(snapshot -> currentDate.equals(snapshot.tradeDate()))
                .flatMap(snapshot -> candidates(snapshot, lane).stream())
                .filter(candidate -> LeaderDirectionEngine.normalizeSymbol(candidate.symbol()).equals(normalized))
                .toList();
        if (matchingSymbols.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前快照中没有该候选");
        }
        return matchingSymbols.stream()
                .filter(candidate -> expectedHash != null && expectedHash.equals(candidate.snapshotHash()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "候选快照已变化，请刷新后重试"));
    }

    private PotentialLeaderSnapshot refresh(CacheKey key, Instant generatedAt) {
        FirstBoardLeaderBaselineAccess baseline;
        try {
            baseline = baselineService.leaderAccess(key.tradeDate());
        } catch (RuntimeException exception) {
            return degraded(key, generatedAt, null, "技术基线读取失败：" + rootMessage(exception));
        }
        if (baseline.status() != FirstBoardAlertStatus.READY) {
            PotentialLeaderSnapshot.Status status = baseline.status() == FirstBoardAlertStatus.FAILED
                    ? PotentialLeaderSnapshot.Status.UNAVAILABLE
                    : PotentialLeaderSnapshot.Status.PREPARING;
            return emptySnapshot(
                    status, key, generatedAt, baseline,
                    baseline.dataGaps().isEmpty() ? List.of("技术基线正在准备") : baseline.dataGaps());
        }
        try {
            if (baseline.stocks().isEmpty()) {
                throw new IllegalStateException("技术基线为空");
            }
            AshareQuoteSnapshot quotes = eastMoneyClient.fetchAshareQuoteSnapshot(FULL_MARKET_LIMIT);
            validateQuotes(key.tradeDate(), quotes);
            ShortTermLimitUpBoardSnapshot boards = boardService.snapshot(key.tradeDate());
            validateBoards(key.tradeDate(), boards);
            List<PotentialLeaderSnapshot.Direction> directions = directionEngine.rank(
                    quotes.quotes(), boards.stocks(), boards.brokenStocks());
            LeaderCandidateEngine.CandidateUniverse universe = candidateEngine.select(
                    baseline, quotes.quotes(), boards, directions,
                    key.includeGrowth(), key.includeStar());
            DragonTigerGateway.DragonTigerBatch dragonTiger = dragonTigerGateway.fetch(
                    key.tradeDate(), universe.competitionSymbols());
            Map<String, PotentialLeaderSnapshot.Direction> previousDirections = previousDirections(key);
            PotentialLeaderSnapshot.MarketSummary marketSummary = marketSummary(boards.sentiment());
            List<PotentialLeaderSnapshot.Candidate> potential = scoreEngine.rankPotential(
                    universe.potential(), directions, previousDirections);
            List<PotentialLeaderSnapshot.Candidate> competition = scoreEngine.rankCompetition(
                    universe.competition(), directions, marketSummary,
                    dragonTiger.evidenceBySymbol(), previousDirections);
            Instant quoteTimestamp = quoteTimestamp(quotes.quotes(), quotes.fetchedAt());
            List<String> gaps = combinedGaps(boards.dataGaps(), dragonTiger.dataGaps());
            LeaderCandidateEngine.SelectionCounts counts = universe.counts();
            int hiddenCount = Math.max(0,
                    counts.potentialEligibleCount() + counts.competitionEligibleCount()
                            - potential.size() - competition.size());
            PotentialLeaderSnapshot ready = new PotentialLeaderSnapshot(
                    PotentialLeaderSnapshot.Status.READY, key.tradeDate(), key.tradeDate(),
                    quoteTimestamp, dragonTiger.latestDisclosureDate(), generatedAt,
                    tradingClock.currentSession().regularAuctionOpen(), LeaderRuleSet.RULE_VERSION,
                    new PotentialLeaderSnapshot.BoardOptions(key.includeGrowth(), key.includeStar()),
                    marketSummary, directions, potential, competition,
                    new PotentialLeaderSnapshot.Coverage(
                            counts.universeCount(), counts.baselineCount(), counts.scopedCount(),
                            counts.liquidCount(), counts.strongDirectionCount(),
                            counts.potentialEligibleCount(), counts.competitionEligibleCount(),
                            hiddenCount, counts.excludedMissingDataCount()), gaps);
            PotentialLeaderSnapshot calibrated = attachCalibration(ready);
            lastSuccessful.put(key, calibrated);
            return calibrated;
        } catch (RuntimeException exception) {
            return degraded(key, generatedAt, baseline, rootMessage(exception));
        }
    }

    private PotentialLeaderSnapshot attachCalibration(PotentialLeaderSnapshot snapshot) {
        Map<String, BigDecimal> directionScores = new LinkedHashMap<>();
        snapshot.directions().forEach(direction ->
                directionScores.put(direction.industry(), direction.score()));
        List<PotentialLeaderSnapshot.Candidate> potential = snapshot.potentialCandidates().stream()
                .map(candidate -> withCalibration(candidate, learningService.calibration(
                        candidate.lane(), candidate.grade(), directionScores.get(candidate.industry()))))
                .toList();
        List<PotentialLeaderSnapshot.Candidate> competition = snapshot.competitionCandidates().stream()
                .map(candidate -> withCalibration(candidate, learningService.calibration(
                        candidate.lane(), candidate.grade(), directionScores.get(candidate.industry()))))
                .toList();
        return new PotentialLeaderSnapshot(
                snapshot.status(), snapshot.tradeDate(), snapshot.quoteTradeDate(), snapshot.quoteTimestamp(),
                snapshot.dragonTigerDate(), snapshot.generatedAt(), snapshot.autoRefreshAllowed(),
                snapshot.ruleVersion(), snapshot.boardOptions(), snapshot.marketSummary(), snapshot.directions(),
                potential, competition, snapshot.coverage(), snapshot.dataGaps());
    }

    private PotentialLeaderSnapshot.Candidate withCalibration(
            PotentialLeaderSnapshot.Candidate source,
            PotentialLeaderSnapshot.Calibration calibration
    ) {
        return new PotentialLeaderSnapshot.Candidate(
                source.lane(), source.rank(), source.grade(), source.score(), source.symbol(), source.name(),
                source.industry(), source.board(), source.latestPrice(), source.changePercent(),
                source.realtimeVolumeRatio(), source.turnoverRate(), source.amount(),
                source.circulatingMarketValue(), source.ma10(), source.ma20(), source.ma20Slope(),
                source.breakoutPositionPercent(), source.currentlySealed(), source.consecutiveBoards(),
                source.firstSealTime(), source.lastSealTime(), source.sealBreakCount(), source.sealFunds(),
                source.scoreBreakdown(), source.riskDeductions(), source.evidence(), source.dragonTiger(),
                calibration, source.snapshotHash());
    }

    private PotentialLeaderSnapshot degraded(
            CacheKey key,
            Instant generatedAt,
            FirstBoardLeaderBaselineAccess baseline,
            String error
    ) {
        String gap = "潜力龙头核心数据不可用：" + error;
        PotentialLeaderSnapshot previous = lastSuccessful.get(key);
        if (previous == null) {
            return emptySnapshot(
                    PotentialLeaderSnapshot.Status.UNAVAILABLE, key, generatedAt, baseline, List.of(gap));
        }
        return new PotentialLeaderSnapshot(
                PotentialLeaderSnapshot.Status.STALE, previous.tradeDate(), previous.quoteTradeDate(),
                previous.quoteTimestamp(), previous.dragonTigerDate(), generatedAt, false,
                previous.ruleVersion(), previous.boardOptions(), previous.marketSummary(), previous.directions(),
                previous.potentialCandidates(), previous.competitionCandidates(), previous.coverage(),
                combinedGaps(previous.dataGaps(), List.of(gap)));
    }

    private PotentialLeaderSnapshot emptySnapshot(
            PotentialLeaderSnapshot.Status status,
            CacheKey key,
            Instant generatedAt,
            FirstBoardLeaderBaselineAccess baseline,
            List<String> gaps
    ) {
        int universe = baseline == null ? 0 : baseline.universeCount();
        int supported = baseline == null ? 0 : baseline.supportedCount();
        int missing = baseline == null ? 0 : baseline.insufficientHistoryCount();
        return new PotentialLeaderSnapshot(
                status, key.tradeDate(), null, null, null, generatedAt, false,
                LeaderRuleSet.RULE_VERSION,
                new PotentialLeaderSnapshot.BoardOptions(key.includeGrowth(), key.includeStar()),
                null, List.of(), List.of(), List.of(),
                new PotentialLeaderSnapshot.Coverage(
                        universe, supported, 0, 0, 0, 0, 0, 0, missing), gaps);
    }

    private void validateQuotes(LocalDate tradeDate, AshareQuoteSnapshot quotes) {
        if (quotes == null || !quotes.complete() || quotes.quotes().isEmpty()) {
            throw new IllegalStateException("盘中全市场行情不完整");
        }
        boolean aligned = quotes.quotes().stream()
                .filter(quote -> quote != null)
                .allMatch(quote -> tradeDate.equals(quote.tradeDate()));
        if (!aligned) {
            throw new IllegalStateException("全市场行情交易日与扫描日不一致");
        }
    }

    private void validateBoards(LocalDate tradeDate, ShortTermLimitUpBoardSnapshot boards) {
        if (boards == null || !boards.available()) {
            throw new IllegalStateException(boards == null || boards.unavailableReason() == null
                    ? "涨停池不可用" : boards.unavailableReason());
        }
        if (!boards.brokenBoardAvailable()) {
            throw new IllegalStateException("炸板池不可用");
        }
        if (!tradeDate.equals(boards.tradeDate())) {
            throw new IllegalStateException("涨停/炸板池交易日与扫描日不一致");
        }
    }

    private PotentialLeaderSnapshot.MarketSummary marketSummary(ShortTermLimitUpSentiment sentiment) {
        if (sentiment == null || sentiment.brokenCount() == null) {
            throw new IllegalStateException("涨停情绪聚合不完整");
        }
        int totalAttempts = sentiment.limitUpCount() + sentiment.brokenCount();
        BigDecimal sealRate = totalAttempts == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(sentiment.limitUpCount()).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(totalAttempts), 2, RoundingMode.HALF_UP);
        BigDecimal boards2Ratio = sentiment.limitUpCount() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(sentiment.boards2PlusCount()).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(sentiment.limitUpCount()), 2, RoundingMode.HALF_UP);
        return new PotentialLeaderSnapshot.MarketSummary(
                sentiment.limitUpCount(), sentiment.brokenCount(), sentiment.maxConsecutiveBoards(),
                sealRate, boards2Ratio, sentiment.tone());
    }

    private Map<String, PotentialLeaderSnapshot.Direction> previousDirections(CacheKey key) {
        PotentialLeaderSnapshot previous = lastSuccessful.get(key);
        if (previous == null) {
            return Map.of();
        }
        Map<String, PotentialLeaderSnapshot.Direction> directions = new LinkedHashMap<>();
        previous.directions().forEach(direction -> directions.put(direction.industry(), direction));
        return Map.copyOf(directions);
    }

    private Instant quoteTimestamp(List<EastMoneyQuote> quotes, Instant fallback) {
        return quotes.stream().map(EastMoneyQuote::marketTimestamp)
                .filter(value -> value != null)
                .max(Instant::compareTo)
                .orElseGet(() -> fallback == null ? cacheClock.instant() : fallback);
    }

    private List<String> combinedGaps(List<String> first, List<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return List.copyOf(result);
    }

    private List<PotentialLeaderSnapshot.Candidate> candidates(
            PotentialLeaderSnapshot snapshot,
            PotentialLeaderSnapshot.Lane lane
    ) {
        return lane == PotentialLeaderSnapshot.Lane.POTENTIAL
                ? snapshot.potentialCandidates() : snapshot.competitionCandidates();
    }

    private LocalDate effectiveTradeDate() {
        LocalDate today = tradingClock.currentMarketDate();
        return tradingClock.isMarketClosedDay(today) ? tradingClock.previousTradingDay(today) : today;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record CacheKey(LocalDate tradeDate, boolean includeGrowth, boolean includeStar) {
    }

    private record CachedSnapshot(PotentialLeaderSnapshot snapshot, Instant expiresAt) {
    }
}
