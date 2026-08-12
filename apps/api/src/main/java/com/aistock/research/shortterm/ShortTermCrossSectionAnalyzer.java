package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ShortTermCrossSectionAnalyzer {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MIN_PERCENTILE_SAMPLE = 3;

    public ShortTermCrossSectionAnalysis analyze(
            List<EastMoneyQuote> quoteUniverse,
            List<EastMoneyQuote> reviewedQuotes,
            Map<String, List<EastMoneyKLine>> klineMap
    ) {
        List<EastMoneyQuote> universe = safeQuotes(quoteUniverse);
        List<EastMoneyQuote> reviewed = safeQuotes(reviewedQuotes);
        Map<String, List<EastMoneyKLine>> safeKlines = klineMap == null ? Map.of() : klineMap;

        Map<String, RawReturns> rawBySymbol = new LinkedHashMap<>();
        for (EastMoneyQuote quote : reviewed) {
            rawBySymbol.put(quote.symbol(), rawReturns(
                    quote,
                    safeKlines.getOrDefault(quote.symbol(), List.of())
            ));
        }

        Map<String, EastMoneyQuote> quoteBySymbol = reviewed.stream().collect(Collectors.toMap(
                EastMoneyQuote::symbol,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
        ));
        Map<String, ShortTermRelativeStrength> relativeStrength = new LinkedHashMap<>();
        for (EastMoneyQuote quote : reviewed) {
            relativeStrength.put(
                    quote.symbol(),
                    relativeStrength(quote, rawBySymbol, quoteBySymbol)
            );
        }

        Map<String, ShortTermIndustryLeadership> leadership = leadership(universe, reviewed);
        int industryCount = (int) universe.stream()
                .map(EastMoneyQuote::industry)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .count();
        int relativeSampleCount = (int) rawBySymbol.values().stream()
                .filter(raw -> raw.return20() != null)
                .count();
        List<String> gaps = new ArrayList<>();
        if (relativeSampleCount < MIN_PERCENTILE_SAMPLE) {
            gaps.add("20日相对强度有效样本不足3只，仅保留原始收益，不参与排序");
        }
        return new ShortTermCrossSectionAnalysis(
                new ShortTermCrossSectionContext(
                        universe.size(),
                        industryCount,
                        relativeSampleCount,
                        "行业热度与成交额领导力使用未经过个股规则过滤的点时全市场行情；相对强度使用已拉取K线的复核横截面",
                        gaps
                ),
                relativeStrength,
                leadership
        );
    }

    private ShortTermRelativeStrength relativeStrength(
            EastMoneyQuote quote,
            Map<String, RawReturns> rawBySymbol,
            Map<String, EastMoneyQuote> quoteBySymbol
    ) {
        RawReturns raw = rawBySymbol.get(quote.symbol());
        if (raw == null) {
            return ShortTermRelativeStrength.unavailable("相对强度原始收益缺失");
        }
        List<RawReturns> marketCohort = rawBySymbol.values().stream().toList();
        String industry = normalizeIndustry(quote.industry());
        List<RawReturns> industryCohort = industry == null ? List.of() : rawBySymbol.entrySet().stream()
                .filter(entry -> {
                    EastMoneyQuote peer = quoteBySymbol.get(entry.getKey());
                    return peer != null && industry.equals(normalizeIndustry(peer.industry()));
                })
                .map(Map.Entry::getValue)
                .toList();

        BigDecimal market5 = percentile(raw.return5(), values(marketCohort, RawReturns::return5));
        BigDecimal market10 = percentile(raw.return10(), values(marketCohort, RawReturns::return10));
        BigDecimal market20 = percentile(raw.return20(), values(marketCohort, RawReturns::return20));
        BigDecimal industry5 = percentile(raw.return5(), values(industryCohort, RawReturns::return5));
        BigDecimal industry10 = percentile(raw.return10(), values(industryCohort, RawReturns::return10));
        BigDecimal industry20 = percentile(raw.return20(), values(industryCohort, RawReturns::return20));

        List<String> gaps = new ArrayList<>(raw.dataGaps());
        BigDecimal marketScore = weightedPercentile(market5, market10, market20);
        BigDecimal industryScore = weightedPercentile(industry5, industry10, industry20);
        if (marketScore == null) {
            gaps.add("市场横截面样本或5/10/20日收益不足，相对强度不参与排序");
        }
        if (industryScore == null) {
            gaps.add("同行横截面样本不足3只，同行相对强度未计分");
        }
        BigDecimal composite = marketScore == null
                ? null
                : industryScore == null
                ? marketScore
                : marketScore.multiply(new BigDecimal("0.65"))
                .add(industryScore.multiply(new BigDecimal("0.35")));
        BigDecimal contribution = composite == null
                ? ZERO
                : clamp(
                        composite.subtract(new BigDecimal("50"))
                                .divide(new BigDecimal("12.5"), 4, RoundingMode.HALF_UP),
                        new BigDecimal("-4"),
                        new BigDecimal("4")
                );
        return new ShortTermRelativeStrength(
                scale(raw.return5()), scale(raw.return10()), scale(raw.return20()),
                scale(market5), scale(market10), scale(market20),
                scale(industry5), scale(industry10), scale(industry20),
                count(values(marketCohort, RawReturns::return20)),
                count(values(industryCohort, RawReturns::return20)),
                scale(composite), scale(contribution), gaps.stream().distinct().toList()
        );
    }

    private Map<String, ShortTermIndustryLeadership> leadership(
            List<EastMoneyQuote> universe,
            List<EastMoneyQuote> reviewed
    ) {
        Map<String, List<EastMoneyQuote>> byIndustry = universe.stream()
                .filter(quote -> normalizeIndustry(quote.industry()) != null)
                .collect(Collectors.groupingBy(
                        quote -> normalizeIndustry(quote.industry()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, ShortTermIndustryLeadership> result = new LinkedHashMap<>();
        for (EastMoneyQuote quote : reviewed) {
            String industry = normalizeIndustry(quote.industry());
            List<EastMoneyQuote> cohort = industry == null
                    ? List.of() : byIndustry.getOrDefault(industry, List.of()).stream()
                    .filter(item -> item.amount() != null && item.amount().signum() > 0)
                    .toList();
            if (industry == null || cohort.size() < MIN_PERCENTILE_SAMPLE) {
                result.put(quote.symbol(), ShortTermIndustryLeadership.unavailable(
                        industry,
                        industry == null ? "行业分类缺失，成交额领导力不计分" : "同行样本不足3只，成交额领导力不计分"
                ));
                continue;
            }
            List<EastMoneyQuote> ordered = cohort.stream()
                    .sorted(Comparator.comparing(
                                    (EastMoneyQuote item) -> nullToZero(item.amount()),
                                    Comparator.reverseOrder())
                            .thenComparing(EastMoneyQuote::symbol))
                    .toList();
            int amountRank = indexOf(ordered, quote.symbol()) + 1;
            BigDecimal amountPercentile = percentile(
                    quote.amount(),
                    cohort.stream().map(EastMoneyQuote::amount).toList()
            );
            BigDecimal contribution = amountPercentile == null
                    ? ZERO
                    : clamp(
                            amountPercentile.subtract(new BigDecimal("50"))
                                    .divide(new BigDecimal("25"), 4, RoundingMode.HALF_UP),
                            new BigDecimal("-2"),
                            new BigDecimal("2")
                    );
            result.put(quote.symbol(), new ShortTermIndustryLeadership(
                    industry,
                    cohort.size(),
                    amountRank,
                    scale(amountPercentile),
                    scale(contribution),
                    "当前成交额在 " + industry + " 的点时全样本中排名 " + amountRank + "/" + cohort.size()
                            + "；该指标仅软排序，不再硬剔除非前三股票"
            ));
        }
        return result;
    }

    private RawReturns rawReturns(EastMoneyQuote quote, List<EastMoneyKLine> source) {
        if (quote == null || quote.symbol() == null || quote.latestPrice() == null
                || quote.latestPrice().signum() <= 0 || quote.tradeDate() == null) {
            return RawReturns.unavailable("行情价或交易日缺失，无法计算相对强度");
        }
        List<String> gaps = new ArrayList<>();
        long futureBars = source == null ? 0 : source.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.tradeDate().isAfter(quote.tradeDate()))
                .count();
        if (futureBars > 0) {
            gaps.add("已剔除 " + futureBars + " 根晚于行情截止日的未来K线");
        }
        List<EastMoneyKLine> history = source == null ? List.of() : source.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null && row.close().signum() > 0)
                .filter(row -> !row.tradeDate().isAfter(quote.tradeDate()))
                .filter(row -> row.tradeDate().isBefore(quote.tradeDate()))
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        BigDecimal return5 = periodReturn(quote.latestPrice(), history, 5);
        BigDecimal return10 = periodReturn(quote.latestPrice(), history, 10);
        BigDecimal return20 = periodReturn(quote.latestPrice(), history, 20);
        if (return5 == null) gaps.add("5日历史不足");
        if (return10 == null) gaps.add("10日历史不足");
        if (return20 == null) gaps.add("20日历史不足");
        return new RawReturns(return5, return10, return20, gaps);
    }

    private BigDecimal periodReturn(BigDecimal latestPrice, List<EastMoneyKLine> history, int window) {
        if (history == null || history.size() < window) {
            return null;
        }
        BigDecimal baseline = history.get(history.size() - window).close();
        if (baseline == null || baseline.signum() <= 0) {
            return null;
        }
        return latestPrice.subtract(baseline)
                .multiply(HUNDRED)
                .divide(baseline, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedPercentile(BigDecimal value5, BigDecimal value10, BigDecimal value20) {
        if (value5 == null || value10 == null || value20 == null) {
            return null;
        }
        return value5.multiply(new BigDecimal("0.25"))
                .add(value10.multiply(new BigDecimal("0.35")))
                .add(value20.multiply(new BigDecimal("0.40")));
    }

    private BigDecimal percentile(BigDecimal target, List<BigDecimal> rawValues) {
        List<BigDecimal> values = rawValues == null ? List.of() : rawValues.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        if (target == null || values.size() < MIN_PERCENTILE_SAMPLE) {
            return null;
        }
        long below = values.stream().filter(value -> value.compareTo(target) < 0).count();
        long equal = values.stream().filter(value -> value.compareTo(target) == 0).count();
        BigDecimal midpointRank = BigDecimal.valueOf(below)
                .add(BigDecimal.valueOf(Math.max(0, equal - 1L))
                        .divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP));
        return midpointRank.multiply(HUNDRED)
                .divide(BigDecimal.valueOf(values.size() - 1L), 6, RoundingMode.HALF_UP);
    }

    private List<BigDecimal> values(List<RawReturns> rows, Function<RawReturns, BigDecimal> extractor) {
        return rows.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
    }

    private int count(List<BigDecimal> values) {
        return values == null ? 0 : values.size();
    }

    private List<EastMoneyQuote> safeQuotes(List<EastMoneyQuote> quotes) {
        if (quotes == null) return List.of();
        return quotes.stream()
                .filter(quote -> quote != null && quote.symbol() != null && !quote.symbol().isBlank())
                .toList();
    }

    private String normalizeIndustry(String industry) {
        return industry == null || industry.isBlank() ? null : industry.trim();
    }

    private int indexOf(List<EastMoneyQuote> quotes, String symbol) {
        for (int index = 0; index < quotes.size(); index++) {
            if (java.util.Objects.equals(quotes.get(index).symbol(), symbol)) return index;
        }
        return -1;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        if (value == null) return ZERO;
        return value.max(minimum).min(maximum);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record RawReturns(
            BigDecimal return5,
            BigDecimal return10,
            BigDecimal return20,
            List<String> dataGaps
    ) {
        private static RawReturns unavailable(String reason) {
            return new RawReturns(null, null, null, List.of(reason));
        }
    }
}
