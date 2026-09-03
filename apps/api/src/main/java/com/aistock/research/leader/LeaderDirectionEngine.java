package com.aistock.research.leader;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermBrokenBoardStock;
import com.aistock.research.shortterm.ShortTermLimitUpStock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LeaderDirectionEngine {

    private static final String UNKNOWN = "UNKNOWN";
    private static final int MIN_CONSTITUENTS = 5;

    public List<PotentialLeaderSnapshot.Direction> rank(
            List<EastMoneyQuote> quotes,
            List<ShortTermLimitUpStock> sealed,
            List<ShortTermBrokenBoardStock> broken
    ) {
        Map<String, EastMoneyQuote> tradableBySymbol = new LinkedHashMap<>();
        Map<String, List<EastMoneyQuote>> byIndustry = new LinkedHashMap<>();
        for (EastMoneyQuote quote : safe(quotes)) {
            if (!tradable(quote)) {
                continue;
            }
            String industry = normalizeIndustry(quote.industry());
            if (UNKNOWN.equals(industry)) {
                continue;
            }
            String symbol = normalizeSymbol(quote.symbol());
            tradableBySymbol.putIfAbsent(symbol, quote);
            byIndustry.computeIfAbsent(industry, ignored -> new ArrayList<>()).add(quote);
        }

        Map<String, ShortTermLimitUpStock> sealedBySymbol = new LinkedHashMap<>();
        for (ShortTermLimitUpStock stock : safe(sealed)) {
            String symbol = normalizeSymbol(stock.symbol());
            if (tradableBySymbol.containsKey(symbol)) {
                sealedBySymbol.putIfAbsent(symbol, stock);
            }
        }
        Map<String, ShortTermBrokenBoardStock> brokenBySymbol = new LinkedHashMap<>();
        for (ShortTermBrokenBoardStock stock : safe(broken)) {
            String symbol = normalizeSymbol(stock.symbol());
            if (tradableBySymbol.containsKey(symbol)) {
                brokenBySymbol.putIfAbsent(symbol, stock);
            }
        }

        List<IndustryMetrics> metrics = byIndustry.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= MIN_CONSTITUENTS)
                .map(entry -> metrics(entry.getKey(), entry.getValue(), sealedBySymbol, brokenBySymbol))
                .toList();
        if (metrics.isEmpty()) {
            return List.of();
        }
        int globalMaxBoard = sealedBySymbol.values().stream()
                .mapToInt(ShortTermLimitUpStock::consecutiveBoards)
                .max()
                .orElse(0);
        List<BigDecimal> breadthValues = metrics.stream().map(IndustryMetrics::breadthRatio).toList();
        List<BigDecimal> medianValues = metrics.stream().map(IndustryMetrics::medianChange).toList();
        List<BigDecimal> sealedValues = metrics.stream().map(IndustryMetrics::sealedRatio).toList();
        List<BigDecimal> brokenValues = metrics.stream().map(IndustryMetrics::positiveBrokenRatio).toList();
        List<BigDecimal> amountValues = metrics.stream().map(IndustryMetrics::amount).toList();

        List<ScoredDirection> scored = metrics.stream().map(metric -> {
            BigDecimal breadth = LeaderRuleSet.percentile(metric.breadthRatio(), breadthValues)
                    .multiply(new BigDecimal("25"));
            BigDecimal relativeChange = LeaderRuleSet.percentile(metric.medianChange(), medianValues)
                    .multiply(new BigDecimal("25"));
            BigDecimal ladder = LeaderRuleSet.percentile(metric.sealedRatio(), sealedValues)
                    .multiply(new BigDecimal("10"))
                    .add(LeaderRuleSet.percentile(metric.positiveBrokenRatio(), brokenValues)
                            .multiply(new BigDecimal("5")));
            if (globalMaxBoard > 0) {
                ladder = ladder.add(BigDecimal.valueOf(metric.maxBoard())
                        .multiply(BigDecimal.TEN)
                        .divide(BigDecimal.valueOf(globalMaxBoard), 8, RoundingMode.HALF_UP));
            }
            BigDecimal participation = LeaderRuleSet.percentile(metric.amount(), amountValues)
                    .multiply(BigDecimal.TEN);
            BigDecimal score = LeaderRuleSet.scale(breadth
                    .add(relativeChange)
                    .add(ladder)
                    .add(metric.continuityPoints())
                    .add(participation));
            return new ScoredDirection(metric, score);
        }).sorted(Comparator
                .comparing(ScoredDirection::score).reversed()
                .thenComparing(row -> row.metrics().limitUpCount(), Comparator.reverseOrder())
                .thenComparing(row -> row.metrics().amount(), Comparator.reverseOrder())
                .thenComparing(row -> row.metrics().industry()))
                .toList();

        List<PotentialLeaderSnapshot.Direction> result = new ArrayList<>();
        for (int index = 0; index < scored.size() && index < 5; index++) {
            ScoredDirection row = scored.get(index);
            if (row.score().compareTo(LeaderRuleSet.MIN_DIRECTION_SCORE) < 0) {
                continue;
            }
            IndustryMetrics metric = row.metrics();
            result.add(new PotentialLeaderSnapshot.Direction(
                    metric.industry(), index + 1, row.score(),
                    LeaderRuleSet.scale(metric.breadthRatio().multiply(LeaderRuleSet.HUNDRED)),
                    LeaderRuleSet.scale(metric.medianChange()), metric.limitUpCount(),
                    metric.brokenCount(), metric.maxBoard(), LeaderRuleSet.scale(metric.amount()),
                    metric.constituentSymbols()));
        }
        return List.copyOf(result);
    }

    private IndustryMetrics metrics(
            String industry,
            List<EastMoneyQuote> quotes,
            Map<String, ShortTermLimitUpStock> sealedBySymbol,
            Map<String, ShortTermBrokenBoardStock> brokenBySymbol
    ) {
        Set<String> symbols = new LinkedHashSet<>();
        List<BigDecimal> changes = new ArrayList<>();
        int positive = 0;
        BigDecimal amount = BigDecimal.ZERO;
        List<ShortTermLimitUpStock> sealed = new ArrayList<>();
        int positiveBroken = 0;
        int brokenCount = 0;
        for (EastMoneyQuote quote : quotes) {
            String symbol = normalizeSymbol(quote.symbol());
            symbols.add(symbol);
            changes.add(quote.changePercent());
            if (quote.changePercent().signum() > 0) {
                positive++;
            }
            amount = amount.add(quote.amount());
            ShortTermLimitUpStock sealedStock = sealedBySymbol.get(symbol);
            if (sealedStock != null) {
                sealed.add(sealedStock);
            }
            ShortTermBrokenBoardStock brokenStock = brokenBySymbol.get(symbol);
            if (brokenStock != null) {
                brokenCount++;
                if (brokenStock.changePercent() != null && brokenStock.changePercent().signum() > 0) {
                    positiveBroken++;
                }
            }
        }
        int size = quotes.size();
        BigDecimal breadthRatio = ratio(positive, size);
        BigDecimal sealedRatio = ratio(sealed.size(), size);
        BigDecimal positiveBrokenRatio = ratio(positiveBroken, size);
        int maxBoard = sealed.stream().mapToInt(ShortTermLimitUpStock::consecutiveBoards).max().orElse(0);
        Set<Integer> boardLevels = new LinkedHashSet<>();
        sealed.stream().map(ShortTermLimitUpStock::consecutiveBoards)
                .filter(level -> level > 0).forEach(boardLevels::add);
        BigDecimal continuity = BigDecimal.ZERO;
        if (boardLevels.contains(1)) {
            continuity = continuity.add(new BigDecimal("5"));
        }
        if (boardLevels.stream().anyMatch(level -> level >= 2)) {
            continuity = continuity.add(new BigDecimal("5"));
        }
        if (maxBoard > 0) {
            continuity = continuity.add(BigDecimal.valueOf(boardLevels.size())
                    .multiply(new BigDecimal("5"))
                    .divide(BigDecimal.valueOf(maxBoard), 8, RoundingMode.HALF_UP));
        }
        return new IndustryMetrics(
                industry, breadthRatio, median(changes), sealedRatio, positiveBrokenRatio,
                sealed.size(), brokenCount, maxBoard, amount, LeaderRuleSet.scale(continuity),
                symbols.stream().sorted().toList());
    }

    static String normalizeIndustry(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim();
        return normalized.equalsIgnoreCase(UNKNOWN) ? UNKNOWN : normalized;
    }

    static String normalizeSymbol(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() <= 6 ? digits : digits.substring(digits.length() - 6);
    }

    private boolean tradable(EastMoneyQuote quote) {
        return quote != null
                && !normalizeSymbol(quote.symbol()).isBlank()
                && quote.latestPrice() != null && quote.latestPrice().signum() > 0
                && quote.changePercent() != null
                && quote.volume() != null && quote.volume().signum() > 0
                && quote.amount() != null && quote.amount().signum() > 0;
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record IndustryMetrics(
            String industry,
            BigDecimal breadthRatio,
            BigDecimal medianChange,
            BigDecimal sealedRatio,
            BigDecimal positiveBrokenRatio,
            int limitUpCount,
            int brokenCount,
            int maxBoard,
            BigDecimal amount,
            BigDecimal continuityPoints,
            List<String> constituentSymbols
    ) {
    }

    private record ScoredDirection(IndustryMetrics metrics, BigDecimal score) {
    }
}
