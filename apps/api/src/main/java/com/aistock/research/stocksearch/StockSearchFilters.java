package com.aistock.research.stocksearch;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;

import java.math.BigDecimal;
import java.util.Locale;

public record StockSearchFilters(
        boolean excludeChiNext,
        boolean excludeStar,
        boolean excludeBeijing,
        boolean excludeSt,
        BigDecimal minChangePercent,
        BigDecimal maxChangePercent,
        BigDecimal minMarketCap,
        BigDecimal maxMarketCap,
        BigDecimal minCirculatingMarketCap,
        BigDecimal maxCirculatingMarketCap,
        BigDecimal minTurnoverRate,
        BigDecimal maxTurnoverRate,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
    private static final BigDecimal YUAN_PER_YI = new BigDecimal("100000000");

    public StockSearchFilters {
        validate("涨幅", minChangePercent, maxChangePercent, true);
        validate("总市值", minMarketCap, maxMarketCap, false);
        validate("流通市值", minCirculatingMarketCap, maxCirculatingMarketCap, false);
        validate("换手率", minTurnoverRate, maxTurnoverRate, false);
        validate("股价", minPrice, maxPrice, false);
        validate("成交额", minAmount, maxAmount, false);
    }

    public static StockSearchFilters none() {
        return new StockSearchFilters(false, false, false, false,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean hasConditions() {
        return excludeChiNext || excludeStar || excludeBeijing || excludeSt
                || minChangePercent != null || maxChangePercent != null || minMarketCap != null || maxMarketCap != null
                || minCirculatingMarketCap != null || maxCirculatingMarketCap != null
                || minTurnoverRate != null || maxTurnoverRate != null || minPrice != null || maxPrice != null
                || minAmount != null || maxAmount != null;
    }

    boolean allows(StockDirectorySnapshot.Stock stock) {
        String symbol = stock.symbol();
        return !(excludeChiNext && symbol.startsWith("30"))
                && !(excludeStar && symbol.startsWith("68"))
                && !(excludeBeijing && (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("92")))
                && !(excludeSt && stock.name().toUpperCase(Locale.ROOT).contains("ST"));
    }

    Match evaluate(EastMoneyQuote quote) {
        Match[] results = {
                within(quote.changePercent(), minChangePercent, maxChangePercent, BigDecimal.ONE),
                within(quote.totalMarketValue(), minMarketCap, maxMarketCap, YUAN_PER_YI),
                within(quote.circulatingMarketValue(), minCirculatingMarketCap, maxCirculatingMarketCap, YUAN_PER_YI),
                within(quote.turnoverRate(), minTurnoverRate, maxTurnoverRate, BigDecimal.ONE),
                within(quote.latestPrice(), minPrice, maxPrice, BigDecimal.ONE),
                within(quote.amount(), minAmount, maxAmount, YUAN_PER_YI)
        };
        boolean unverified = false;
        for (Match result : results) {
            if (result == Match.EXCLUDED) return Match.EXCLUDED;
            if (result == Match.UNVERIFIED) unverified = true;
        }
        return unverified ? Match.UNVERIFIED : Match.MATCH;
    }

    private static Match within(BigDecimal value, BigDecimal minimum, BigDecimal maximum, BigDecimal unit) {
        if (minimum == null && maximum == null) return Match.MATCH;
        if (value == null) return Match.UNVERIFIED;
        if (minimum != null && value.compareTo(minimum.multiply(unit)) < 0) return Match.EXCLUDED;
        if (maximum != null && value.compareTo(maximum.multiply(unit)) > 0) return Match.EXCLUDED;
        return Match.MATCH;
    }

    private static void validate(String label, BigDecimal minimum, BigDecimal maximum, boolean allowNegative) {
        if (!allowNegative && (minimum != null && minimum.signum() < 0 || maximum != null && maximum.signum() < 0)) {
            throw new IllegalArgumentException(label + "不能小于0");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + "下限不能大于上限");
        }
    }

    enum Match { MATCH, EXCLUDED, UNVERIFIED }
}
