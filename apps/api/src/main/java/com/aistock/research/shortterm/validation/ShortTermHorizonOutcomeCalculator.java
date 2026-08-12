package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.MarketBar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Component
public class ShortTermHorizonOutcomeCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public ShortTermHorizonEvaluation evaluate(
            String horizon,
            LocalDate recommendationTradeDate,
            LocalDate targetTradeDate,
            BigDecimal baselinePrice,
            List<MarketBar> source,
            ShortTermValidationCostAssumptions costs
    ) {
        if (!positive(baselinePrice)) {
            return ShortTermHorizonEvaluation.unavailable(
                    horizon, "UNAVAILABLE_BASELINE", targetTradeDate, "推荐基准价缺失或无效");
        }
        if (recommendationTradeDate == null || targetTradeDate == null
                || !targetTradeDate.isAfter(recommendationTradeDate)) {
            return ShortTermHorizonEvaluation.unavailable(
                    horizon, "UNAVAILABLE_TARGET_DATE", targetTradeDate, "推荐日或目标交易日无效");
        }

        List<MarketBar> window = (source == null ? List.<MarketBar>of() : source).stream()
                .filter(this::valid)
                .filter(bar -> bar.tradeDate().isAfter(recommendationTradeDate))
                .filter(bar -> !bar.tradeDate().isAfter(targetTradeDate))
                .sorted(Comparator.comparing(MarketBar::tradeDate))
                .toList();
        MarketBar target = window.stream()
                .filter(bar -> targetTradeDate.equals(bar.tradeDate()))
                .reduce((first, last) -> last)
                .orElse(null);
        if (target == null) {
            return ShortTermHorizonEvaluation.unavailable(
                    horizon,
                    "UNAVAILABLE_SUSPENDED_OR_MISSING",
                    targetTradeDate,
                    "目标市场交易日无有效日K线，禁止顺延到后续交易日"
            );
        }

        BigDecimal highest = window.stream()
                .map(MarketBar::high)
                .max(BigDecimal::compareTo)
                .orElse(target.high());
        BigDecimal lowest = window.stream()
                .map(MarketBar::low)
                .min(BigDecimal::compareTo)
                .orElse(target.low());
        BigDecimal grossReturn = percent(target.close().subtract(baselinePrice), baselinePrice);
        BigDecimal netReturn = netReturn(baselinePrice, target.close(), costs);
        return new ShortTermHorizonEvaluation(
                horizon,
                "MATURED",
                targetTradeDate,
                money(target.close()),
                percentScale(grossReturn),
                percentScale(netReturn),
                percentScale(percent(highest.subtract(baselinePrice), baselinePrice)),
                percentScale(percent(lowest.subtract(baselinePrice), baselinePrice)),
                "按推荐时固化的双边佣金、卖出印花税和双边滑点计算"
        );
    }

    private BigDecimal netReturn(
            BigDecimal baseline,
            BigDecimal evaluation,
            ShortTermValidationCostAssumptions sourceCosts
    ) {
        ShortTermValidationCostAssumptions costs = sourceCosts == null
                ? new ShortTermValidationCostAssumptions(null, null, null, null, null)
                : sourceCosts;
        BigDecimal effectiveBuy = baseline
                .multiply(BigDecimal.ONE.add(rate(costs.buySlippagePercent())))
                .multiply(BigDecimal.ONE.add(rate(costs.buyCommissionPercent())));
        BigDecimal sellChargeRate = rate(costs.sellCommissionPercent())
                .add(rate(costs.sellStampDutyPercent()));
        BigDecimal effectiveSell = evaluation
                .multiply(BigDecimal.ONE.subtract(rate(costs.sellSlippagePercent())))
                .multiply(BigDecimal.ONE.subtract(sellChargeRate));
        return percent(effectiveSell.subtract(effectiveBuy), effectiveBuy);
    }

    private BigDecimal rate(BigDecimal percent) {
        return percent.divide(HUNDRED, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(HUNDRED).divide(denominator, 10, RoundingMode.HALF_UP);
    }

    private boolean valid(MarketBar bar) {
        return bar != null && bar.tradeDate() != null
                && positive(bar.close()) && positive(bar.high()) && positive(bar.low());
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal percentScale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }
}
