package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class TradeLedgerCalculator {

    private static final int COST_SCALE = 6;

    public TradeLedgerSummary calculate(List<LedgerFill> fills, BigDecimal latestPrice) {
        if (latestPrice != null && latestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("最新价格必须大于零");
        }
        List<LedgerFill> orderedFills = new ArrayList<>(fills == null ? List.of() : fills);
        orderedFills.forEach(this::validate);
        orderedFills.sort(Comparator.comparing(LedgerFill::executedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(LedgerFill::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));

        long position = 0;
        BigDecimal averageCost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        for (LedgerFill fill : orderedFills) {
            if (fill.side() == TradeSide.BUY) {
                BigDecimal oldCost = averageCost.multiply(BigDecimal.valueOf(position));
                BigDecimal newCost = fill.price().multiply(BigDecimal.valueOf(fill.quantity()));
                position += fill.quantity();
                averageCost = oldCost.add(newCost)
                        .divide(BigDecimal.valueOf(position), COST_SCALE, RoundingMode.HALF_UP);
            } else {
                if (fill.quantity() > position) {
                    throw new IllegalArgumentException("卖出股数超过当前持仓");
                }
                realized = realized.add(fill.price().subtract(averageCost)
                        .multiply(BigDecimal.valueOf(fill.quantity())));
                position -= fill.quantity();
                if (position == 0) {
                    averageCost = BigDecimal.ZERO;
                }
            }
        }

        BigDecimal unrealized = latestPrice == null
                ? null
                : latestPrice.subtract(averageCost).multiply(BigDecimal.valueOf(position));
        BigDecimal total = position == 0 && !orderedFills.isEmpty()
                ? realized
                : unrealized == null ? null : realized.add(unrealized);
        Instant openedAt = orderedFills.stream()
                .filter(fill -> fill.side() == TradeSide.BUY)
                .map(LedgerFill::executedAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new TradeLedgerSummary(latestPrice, position, averageCost, realized, unrealized, total, openedAt);
    }

    public TradeLedgerSummary calculate(Collection<TradeFillEntity> fills, BigDecimal latestPrice) {
        List<LedgerFill> ledgerFills = (fills == null ? List.<TradeFillEntity>of() : fills).stream()
                .map(this::toLedgerFill)
                .toList();
        return calculate(ledgerFills, latestPrice);
    }

    private LedgerFill toLedgerFill(TradeFillEntity fill) {
        if (fill == null) {
            throw new IllegalArgumentException("成交记录不能为空");
        }
        if (fill.getSide() == null) {
            throw new IllegalArgumentException("成交方向必须为 BUY 或 SELL");
        }
        try {
            return new LedgerFill(
                    TradeSide.valueOf(fill.getSide()),
                    fill.getExecutedAt(),
                    fill.getPrice(),
                    fill.getQuantity(),
                    fill.getCreatedAt()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("成交方向必须为 BUY 或 SELL", exception);
        }
    }

    private void validate(LedgerFill fill) {
        if (fill == null) {
            throw new IllegalArgumentException("成交记录不能为空");
        }
        if (fill.side() == null) {
            throw new IllegalArgumentException("成交方向必须为 BUY 或 SELL");
        }
        if (fill.executedAt() == null || fill.createdAt() == null) {
            throw new IllegalArgumentException("成交时间不能为空");
        }
        if (fill.price() == null || fill.price().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("成交价格必须大于零");
        }
        if (fill.quantity() <= 0) {
            throw new IllegalArgumentException("成交股数必须为正整数");
        }
    }
}
