package com.aistock.research.quality;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public final class RecommendationQuality {

    public static final BigDecimal MIN_RECOMMENDED_AMOUNT = new BigDecimal("80000000");
    private static final int SIDEWAYS_MIN_ROWS = 80;
    private static final int SIDEWAYS_WINDOW = 120;
    private static final BigDecimal SIDEWAYS_RANGE_PERCENT = new BigDecimal("22");
    private static final BigDecimal SIDEWAYS_NET_CHANGE_PERCENT = new BigDecimal("8");

    private RecommendationQuality() {
    }

    public static boolean hasSufficientLiquidity(EastMoneyQuote quote) {
        return quote != null && hasSufficientLiquidity(quote.amount());
    }

    public static boolean hasSufficientLiquidity(BigDecimal amount) {
        return amount != null && amount.compareTo(MIN_RECOMMENDED_AMOUNT) >= 0;
    }

    public static BigDecimal requiredAmount(BigDecimal configuredMinAmount) {
        if (configuredMinAmount == null || configuredMinAmount.compareTo(MIN_RECOMMENDED_AMOUNT) < 0) {
            return MIN_RECOMMENDED_AMOUNT;
        }
        return configuredMinAmount;
    }

    public static boolean hasSufficientLiquidity(EastMoneyQuote quote, BigDecimal configuredMinAmount) {
        return quote != null && quote.amount() != null && quote.amount().compareTo(requiredAmount(configuredMinAmount)) >= 0;
    }

    public static boolean isLongSideways(List<EastMoneyKLine> rows) {
        List<EastMoneyKLine> sorted = rows == null ? List.of() : rows.stream()
                .filter(row -> row.tradeDate() != null && row.close() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (sorted.size() < SIDEWAYS_MIN_ROWS) {
            return false;
        }
        List<EastMoneyKLine> window = sorted.subList(Math.max(0, sorted.size() - SIDEWAYS_WINDOW), sorted.size());
        BigDecimal high = window.stream()
                .map(row -> firstPositive(row.high(), row.close()))
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal low = window.stream()
                .map(row -> firstPositive(row.low(), row.close()))
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal firstClose = window.get(0).close();
        BigDecimal lastClose = window.get(window.size() - 1).close();
        BigDecimal rangePercent = percent(high == null || low == null ? null : high.subtract(low), low);
        BigDecimal netChangePercent = percent(lastClose.subtract(firstClose).abs(), firstClose);
        return rangePercent != null
                && netChangePercent != null
                && rangePercent.compareTo(SIDEWAYS_RANGE_PERCENT) <= 0
                && netChangePercent.compareTo(SIDEWAYS_NET_CHANGE_PERCENT) <= 0;
    }

    public static String liquidityRiskText() {
        return "成交额低于 8000 万或盘前为空，流动性不足，容易出现滑点、价差扩大和假突破。";
    }

    public static String sidewaysRiskText() {
        return "近 80 个以上交易日长期横盘震荡且缺少有效突破，资金效率低，不纳入荐股候选。";
    }

    private static BigDecimal firstPositive(BigDecimal primary, BigDecimal fallback) {
        return primary != null && primary.compareTo(BigDecimal.ZERO) > 0 ? primary : fallback;
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }
}
