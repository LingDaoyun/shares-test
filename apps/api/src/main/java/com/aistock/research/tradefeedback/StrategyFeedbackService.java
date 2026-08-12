package com.aistock.research.tradefeedback;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class StrategyFeedbackService {

    private static final String SHORT_TERM_SOURCE = "SHORT_TERM";
    private static final String BUY = "BUY";
    private static final ZoneId SAMPLE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int METRIC_SCALE = 4;
    private static final int PROMPT_COHORT_LIMIT = 12;
    private static final int DEFAULT_MAX_ROWS = 5_000;
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    private static final BigDecimal MIN_ADJUSTMENT = new BigDecimal("-5");
    private static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("5");
    private static final Comparator<TradeFillSnapshot> FIRST_FILL_ORDER = Comparator
            .comparing(TradeFillSnapshot::executedAt)
            .thenComparing(TradeFillSnapshot::createdAt)
            .thenComparing(TradeFillSnapshot::fillId);
    private static final Comparator<StrategyFeedbackSummary> PROMPT_COHORT_ORDER = Comparator
            .comparingInt(StrategyFeedbackSummary::sampleCount)
            .reversed()
            .thenComparing(StrategyFeedbackSummary::sourceModule)
            .thenComparing(StrategyFeedbackSummary::ruleVersion)
            .thenComparing(StrategyFeedbackSummary::horizon);

    private final TradeOutcomeRepository outcomeRepository;
    private final TradeFillRepository fillRepository;
    private final TradeFillRevisionRepository revisionRepository;
    private final TradeFillProjector fillProjector;
    private final Clock clock;
    private final int maxRows;
    private final Duration cacheTtl;
    private volatile CacheEntry cache;

    @Autowired
    public StrategyFeedbackService(
            TradeOutcomeRepository outcomeRepository,
            TradeFillRepository fillRepository,
            TradeFillRevisionRepository revisionRepository,
            TradeFillProjector fillProjector,
            @Value("${trade-feedback.feedback.max-rows:5000}") int maxRows,
            @Value("${trade-feedback.feedback.cache-ttl:PT10M}") Duration cacheTtl
    ) {
        this(outcomeRepository, fillRepository, revisionRepository, fillProjector,
                Clock.systemUTC(), maxRows, cacheTtl);
    }

    StrategyFeedbackService(
            TradeOutcomeRepository outcomeRepository,
            TradeFillRepository fillRepository,
            TradeFillRevisionRepository revisionRepository,
            TradeFillProjector fillProjector,
            Clock clock,
            int maxRows,
            Duration cacheTtl
    ) {
        this.outcomeRepository = outcomeRepository;
        this.fillRepository = fillRepository;
        this.revisionRepository = revisionRepository;
        this.fillProjector = fillProjector;
        this.clock = clock;
        if (maxRows <= 0) {
            throw new IllegalArgumentException("策略反馈样本上限必须为正整数");
        }
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("策略反馈缓存时间必须为正数");
        }
        this.maxRows = maxRows;
        this.cacheTtl = cacheTtl;
    }

    StrategyFeedbackService(
            TradeOutcomeRepository outcomeRepository,
            TradeFillRepository fillRepository
    ) {
        this(outcomeRepository, fillRepository, null, new TradeFillProjector(),
                Clock.systemUTC(), DEFAULT_MAX_ROWS, DEFAULT_CACHE_TTL);
    }

    @Transactional(readOnly = true)
    public List<StrategyFeedbackSummary> summaries() {
        CacheEntry current = cache;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.summaries();
        }
        return refreshCache(now);
    }

    private synchronized List<StrategyFeedbackSummary> refreshCache(Instant now) {
        CacheEntry current = cache;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.summaries();
        }
        List<MaturedRecommendationRow> rows = outcomeRepository
                .findMaturedRecommendationT20(PageRequest.of(0, maxRows)).stream()
                .filter(row -> !SHORT_TERM_SOURCE.equals(row.sourceModule()))
                .filter(row -> row.returnPct() != null)
                .toList();
        List<MaturedRecommendationRow> shortTermRows = outcomeRepository
                .findMaturedShortTermRecommendationT1T2(PageRequest.of(0, maxRows)).stream()
                .filter(row -> row.returnPct() != null)
                .toList();
        if (!shortTermRows.isEmpty()) {
            List<MaturedRecommendationRow> merged = new ArrayList<>(rows.size() + shortTermRows.size());
            merged.addAll(rows);
            merged.addAll(shortTermRows);
            rows = List.copyOf(merged);
        }
        if (rows.isEmpty()) {
            return cache(now, List.of());
        }

        Collection<String> caseIds = rows.stream()
                .map(MaturedRecommendationRow::caseId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Map<String, TradeFillSnapshot> firstBuyByCase = firstBuys(activeFills(caseIds));

        Map<CohortKey, List<MaturedRecommendationRow>> cohorts = new TreeMap<>();
        for (MaturedRecommendationRow row : rows) {
            cohorts.computeIfAbsent(new CohortKey(row.sourceModule(), row.ruleVersion(), row.horizon()), ignored -> new ArrayList<>())
                    .add(row);
        }

        List<StrategyFeedbackSummary> summaries = cohorts.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue(), firstBuyByCase))
                .toList();
        return cache(now, summaries);
    }

    @Transactional(readOnly = true)
    public List<StrategyFeedbackSummary> promptContext(String symbol) {
        return summaries().stream()
                .filter(StrategyFeedbackSummary::promptEligible)
                .sorted(PROMPT_COHORT_ORDER)
                .limit(PROMPT_COHORT_LIMIT)
                .toList();
    }

    private List<StrategyFeedbackSummary> cache(Instant now, List<StrategyFeedbackSummary> summaries) {
        List<StrategyFeedbackSummary> immutable = List.copyOf(summaries);
        cache = new CacheEntry(immutable, now.plus(cacheTtl));
        return immutable;
    }

    private StrategyFeedbackSummary summarize(
            CohortKey key,
            List<MaturedRecommendationRow> rows,
            Map<String, TradeFillSnapshot> firstBuyByCase
    ) {
        List<BigDecimal> returns = rows.stream()
                .map(MaturedRecommendationRow::returnPct)
                .sorted()
                .toList();
        int sampleCount = returns.size();
        int positiveCount = (int) returns.stream().filter(value -> value.signum() > 0).count();
        BigDecimal positiveRate = BigDecimal.valueOf(positiveCount)
                .divide(BigDecimal.valueOf(sampleCount), METRIC_SCALE, RoundingMode.HALF_UP);
        BigDecimal medianReturn = median(returns);
        List<BigDecimal> deviations = rows.stream()
                .map(row -> executionDeviation(row, firstBuyByCase.get(row.caseId())))
                .filter(value -> value != null)
                .toList();
        LocalDate sampleStart = rows.stream()
                .map(row -> row.recommendedAt().atZone(SAMPLE_ZONE).toLocalDate())
                .min(LocalDate::compareTo)
                .orElseThrow();
        LocalDate sampleEnd = rows.stream()
                .map(row -> row.recommendedAt().atZone(SAMPLE_ZONE).toLocalDate())
                .max(LocalDate::compareTo)
                .orElseThrow();
        boolean adjustmentEligible = sampleCount >= 20;

        return new StrategyFeedbackSummary(
                key.sourceModule(),
                key.ruleVersion(),
                key.horizon(),
                sampleCount,
                positiveCount,
                positiveRate,
                average(returns),
                medianReturn,
                average(values(rows, MaturedRecommendationRow::maxRunupPct)),
                average(values(rows, MaturedRecommendationRow::maxDrawdownPct)),
                average(deviations),
                deviations.size(),
                sampleStart,
                sampleEnd,
                sampleCount >= 5,
                adjustmentEligible,
                adjustmentEligible ? reliabilityAdjustment(positiveRate, medianReturn) : null
        );
    }

    private Map<String, TradeFillSnapshot> firstBuys(List<TradeFillSnapshot> fills) {
        Map<String, TradeFillSnapshot> firstByCase = new LinkedHashMap<>();
        fills.stream()
                .filter(fill -> BUY.equals(fill.side()))
                .forEach(fill -> firstByCase.merge(
                        fill.caseId(),
                        fill,
                        (left, right) -> FIRST_FILL_ORDER.compare(left, right) <= 0 ? left : right));
        return firstByCase;
    }

    private BigDecimal executionDeviation(MaturedRecommendationRow row, TradeFillSnapshot firstBuy) {
        if (firstBuy == null || row.recommendedPrice() == null || row.recommendedPrice().signum() == 0) {
            return null;
        }
        return firstBuy.price()
                .subtract(row.recommendedPrice())
                .divide(row.recommendedPrice(), METRIC_SCALE + 2, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    private List<TradeFillSnapshot> activeFills(Collection<String> caseIds) {
        List<TradeFillEntity> originals = fillRepository
                .findByCaseIdInOrderByCaseIdAscExecutedAtAscCreatedAtAscFillIdAsc(caseIds);
        List<TradeFillRevisionEntity> revisions = revisionRepository == null
                ? List.of()
                : revisionRepository.findByCaseIdInOrderByCaseIdAscRevisionSequenceAsc(caseIds);
        Map<String, List<TradeFillEntity>> originalsByCase = originals.stream()
                .collect(java.util.stream.Collectors.groupingBy(TradeFillEntity::getCaseId));
        Map<String, List<TradeFillRevisionEntity>> revisionsByCase = revisions.stream()
                .collect(java.util.stream.Collectors.groupingBy(TradeFillRevisionEntity::getCaseId));
        return caseIds.stream()
                .flatMap(caseId -> fillProjector.project(
                        originalsByCase.getOrDefault(caseId, List.of()),
                        revisionsByCase.getOrDefault(caseId, List.of())).stream())
                .toList();
    }

    private BigDecimal reliabilityAdjustment(BigDecimal positiveRate, BigDecimal medianReturn) {
        BigDecimal raw = positiveRate.subtract(new BigDecimal("0.50"))
                .multiply(BigDecimal.TEN)
                .add(medianReturn.signum() > 0 ? BigDecimal.ONE : BigDecimal.ONE.negate());
        return raw.max(MIN_ADJUSTMENT).min(MAX_ADJUSTMENT).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> sortedValues) {
        int midpoint = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(midpoint).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
        }
        return sortedValues.get(midpoint - 1)
                .add(sortedValues.get(midpoint))
                .divide(new BigDecimal("2"), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    private List<BigDecimal> values(
            List<MaturedRecommendationRow> rows,
            java.util.function.Function<MaturedRecommendationRow, BigDecimal> mapper
    ) {
        return rows.stream().map(mapper).filter(value -> value != null).toList();
    }

    private record CohortKey(String sourceModule, String ruleVersion, String horizon) implements Comparable<CohortKey> {

        @Override
        public int compareTo(CohortKey other) {
            int sourceComparison = sourceModule.compareTo(other.sourceModule);
            if (sourceComparison != 0) {
                return sourceComparison;
            }
            int ruleComparison = ruleVersion.compareTo(other.ruleVersion);
            return ruleComparison != 0 ? ruleComparison : horizon.compareTo(other.horizon);
        }
    }

    private record CacheEntry(List<StrategyFeedbackSummary> summaries, Instant expiresAt) {
    }
}
