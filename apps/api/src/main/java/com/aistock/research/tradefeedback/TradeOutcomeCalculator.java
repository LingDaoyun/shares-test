package com.aistock.research.tradefeedback;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Component
public final class TradeOutcomeCalculator {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int PERCENT_SCALE = 4;
    private static final List<Horizon> HORIZONS = List.of(
            new Horizon("T1", 1),
            new Horizon("T5", 5),
            new Horizon("T20", 20));

    public List<OutcomeResult> evaluateRecommendation(
            BigDecimal recommendedPrice,
            List<MarketBar> rows,
            Instant recommendedAt
    ) {
        requirePositive(recommendedPrice, "推荐价格必须大于零");
        if (recommendedAt == null) {
            throw new IllegalArgumentException("推荐时间不能为空");
        }
        List<MarketBar> tradingRows = tradingRowsAfter(rows, recommendedAt);

        return HORIZONS.stream()
                .map(horizon -> evaluateHorizon(horizon, recommendedPrice, tradingRows))
                .toList();
    }

    public OutcomeResult evaluateRecommendationCurrent(
            BigDecimal recommendedPrice,
            List<MarketBar> rows,
            Instant recommendedAt,
            BigDecimal latestPrice,
            LocalDate evaluationDate
    ) {
        requirePositive(recommendedPrice, "推荐价格必须大于零");
        if (!positive(latestPrice) || evaluationDate == null) {
            return OutcomeResult.pending("CURRENT");
        }
        List<MarketBar> tradingRows = tradingRowsAfter(rows, recommendedAt);
        BigDecimal highest = tradingRows.stream().map(MarketBar::high).max(BigDecimal::compareTo).orElse(null);
        BigDecimal lowest = tradingRows.stream().map(MarketBar::low).min(BigDecimal::compareTo).orElse(null);
        return new OutcomeResult(
                "CURRENT",
                recommendedPrice,
                latestPrice,
                evaluationDate,
                percent(latestPrice.subtract(recommendedPrice), recommendedPrice),
                highest == null ? null : percent(highest.subtract(recommendedPrice), recommendedPrice),
                lowest == null ? null : percent(lowest.subtract(recommendedPrice), recommendedPrice),
                "MATURED"
        );
    }

    public OutcomeResult evaluateExecution(
            List<LedgerFill> fills,
            long positionQuantity,
            BigDecimal latestPrice,
            LocalDate currentEvaluationDate
    ) {
        List<LedgerFill> ordered = (fills == null ? List.<LedgerFill>of() : fills).stream()
                .sorted(Comparator.comparing(LedgerFill::executedAt)
                        .thenComparing(LedgerFill::createdAt))
                .toList();
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("执行结果至少需要一笔成交");
        }
        if (positionQuantity < 0) {
            throw new IllegalArgumentException("当前持仓不能为负数");
        }
        if (positionQuantity > 0) {
            requirePositive(latestPrice, "持仓执行结果需要最新价格");
            if (currentEvaluationDate == null) {
                throw new IllegalArgumentException("持仓执行结果需要评估日期");
            }
        }

        BigDecimal grossBuyAmount = BigDecimal.ZERO;
        long grossBuyQuantity = 0;
        BigDecimal sellAmount = BigDecimal.ZERO;
        for (LedgerFill fill : ordered) {
            BigDecimal amount = fill.price().multiply(BigDecimal.valueOf(fill.quantity()));
            if (fill.side() == TradeSide.BUY) {
                grossBuyAmount = grossBuyAmount.add(amount);
                grossBuyQuantity += fill.quantity();
            } else {
                sellAmount = sellAmount.add(amount);
            }
        }
        requirePositive(grossBuyAmount, "执行结果至少需要一笔买入成交");

        BigDecimal markedPosition = positionQuantity == 0
                ? BigDecimal.ZERO
                : latestPrice.multiply(BigDecimal.valueOf(positionQuantity));
        BigDecimal grossProfit = sellAmount.add(markedPosition).subtract(grossBuyAmount);
        BigDecimal baselinePrice = grossBuyAmount.divide(
                BigDecimal.valueOf(grossBuyQuantity), 6, RoundingMode.HALF_UP);
        LedgerFill lastFill = ordered.get(ordered.size() - 1);
        boolean closed = positionQuantity == 0;
        return new OutcomeResult(
                closed ? "CLOSED" : "CURRENT",
                baselinePrice,
                closed ? lastFill.price() : latestPrice,
                closed ? lastFill.executedAt().atZone(SHANGHAI).toLocalDate() : currentEvaluationDate,
                percent(grossProfit, grossBuyAmount),
                null,
                null,
                "MATURED"
        );
    }

    private OutcomeResult evaluateHorizon(Horizon horizon, BigDecimal baseline, List<MarketBar> rows) {
        if (rows.size() < horizon.rowCount()) {
            return OutcomeResult.pending(horizon.name());
        }
        List<MarketBar> window = rows.subList(0, horizon.rowCount());
        MarketBar evaluation = window.get(window.size() - 1);
        BigDecimal highest = window.stream().map(MarketBar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal lowest = window.stream().map(MarketBar::low).min(BigDecimal::compareTo).orElseThrow();
        return new OutcomeResult(
                horizon.name(),
                baseline,
                evaluation.close(),
                evaluation.tradeDate(),
                percent(evaluation.close().subtract(baseline), baseline),
                percent(highest.subtract(baseline), baseline),
                percent(lowest.subtract(baseline), baseline),
                "MATURED"
        );
    }

    private List<MarketBar> tradingRowsAfter(List<MarketBar> rows, Instant recommendedAt) {
        if (recommendedAt == null) {
            throw new IllegalArgumentException("推荐时间不能为空");
        }
        LocalDate recommendationDate = recommendedAt.atZone(SHANGHAI).toLocalDate();
        return (rows == null ? List.<MarketBar>of() : rows).stream()
                .filter(this::validBar)
                .filter(row -> row.tradeDate().isAfter(recommendationDate))
                .sorted(Comparator.comparing(MarketBar::tradeDate))
                .toList();
    }

    private boolean validBar(MarketBar row) {
        return row != null
                && row.tradeDate() != null
                && positive(row.close())
                && positive(row.high())
                && positive(row.low());
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private void requirePositive(BigDecimal value, String message) {
        if (!positive(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private record Horizon(String name, int rowCount) {
    }
}
