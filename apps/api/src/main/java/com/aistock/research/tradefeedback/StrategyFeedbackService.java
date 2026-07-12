package com.aistock.research.tradefeedback;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final String HORIZON = "T20";
    private static final String BUY = "BUY";
    private static final ZoneId SAMPLE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int METRIC_SCALE = 4;
    private static final int PROMPT_COHORT_LIMIT = 12;
    private static final BigDecimal MIN_ADJUSTMENT = new BigDecimal("-5");
    private static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("5");
    private static final Comparator<TradeFillEntity> FIRST_FILL_ORDER = Comparator
            .comparing(TradeFillEntity::getExecutedAt)
            .thenComparing(TradeFillEntity::getCreatedAt)
            .thenComparing(TradeFillEntity::getFillId);
    private static final Comparator<StrategyFeedbackSummary> PROMPT_COHORT_ORDER = Comparator
            .comparingInt(StrategyFeedbackSummary::sampleCount)
            .reversed()
            .thenComparing(StrategyFeedbackSummary::sourceModule)
            .thenComparing(StrategyFeedbackSummary::ruleVersion);

    private final TradeOutcomeRepository outcomeRepository;
    private final TradeFillRepository fillRepository;

    public StrategyFeedbackService(
            TradeOutcomeRepository outcomeRepository,
            TradeFillRepository fillRepository
    ) {
        this.outcomeRepository = outcomeRepository;
        this.fillRepository = fillRepository;
    }

    @Transactional(readOnly = true)
    public List<StrategyFeedbackSummary> summaries() {
        List<MaturedRecommendationRow> rows = outcomeRepository.findMaturedRecommendationT20().stream()
                .filter(row -> row.returnPct() != null)
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }

        Collection<String> caseIds = rows.stream()
                .map(MaturedRecommendationRow::caseId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Map<String, TradeFillEntity> firstBuyByCase = firstBuys(
                fillRepository.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(caseIds, BUY));

        Map<CohortKey, List<MaturedRecommendationRow>> cohorts = new TreeMap<>();
        for (MaturedRecommendationRow row : rows) {
            cohorts.computeIfAbsent(new CohortKey(row.sourceModule(), row.ruleVersion()), ignored -> new ArrayList<>())
                    .add(row);
        }

        return cohorts.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue(), firstBuyByCase))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StrategyFeedbackSummary> promptContext(String symbol) {
        return summaries().stream()
                .filter(StrategyFeedbackSummary::promptEligible)
                .sorted(PROMPT_COHORT_ORDER)
                .limit(PROMPT_COHORT_LIMIT)
                .toList();
    }

    private StrategyFeedbackSummary summarize(
            CohortKey key,
            List<MaturedRecommendationRow> rows,
            Map<String, TradeFillEntity> firstBuyByCase
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
                HORIZON,
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

    private Map<String, TradeFillEntity> firstBuys(List<TradeFillEntity> fills) {
        Map<String, TradeFillEntity> firstByCase = new LinkedHashMap<>();
        fills.stream()
                .filter(fill -> BUY.equals(fill.getSide()))
                .forEach(fill -> firstByCase.merge(
                        fill.getCaseId(),
                        fill,
                        (left, right) -> FIRST_FILL_ORDER.compare(left, right) <= 0 ? left : right));
        return firstByCase;
    }

    private BigDecimal executionDeviation(MaturedRecommendationRow row, TradeFillEntity firstBuy) {
        if (firstBuy == null || row.recommendedPrice() == null || row.recommendedPrice().signum() == 0) {
            return null;
        }
        return firstBuy.getPrice()
                .subtract(row.recommendedPrice())
                .divide(row.recommendedPrice(), METRIC_SCALE + 2, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(METRIC_SCALE, RoundingMode.HALF_UP);
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

    private record CohortKey(String sourceModule, String ruleVersion) implements Comparable<CohortKey> {

        @Override
        public int compareTo(CohortKey other) {
            int sourceComparison = sourceModule.compareTo(other.sourceModule);
            return sourceComparison != 0 ? sourceComparison : ruleVersion.compareTo(other.ruleVersion);
        }
    }
}
