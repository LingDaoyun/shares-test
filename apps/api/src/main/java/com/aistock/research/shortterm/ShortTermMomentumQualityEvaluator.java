package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ShortTermMomentumQualityEvaluator {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal EIGHT = new BigDecimal("8");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal LARGE_AMOUNT = new BigDecimal("2000000000");

    public ShortTermMomentumQuality evaluate(
            EastMoneyQuote quote,
            List<EastMoneyKLine> source,
            BigDecimal evaluationClose,
            boolean latestBarCompleted
    ) {
        List<String> dataGaps = new ArrayList<>();
        BigDecimal turnoverRate = quote == null ? null : quote.turnoverRate();
        TurnoverEvaluation turnover = evaluateTurnover(quote, turnoverRate);
        if (turnoverRate == null) {
            dataGaps.add("实时换手率缺失");
        }

        List<EastMoneyKLine> rows = source == null ? List.of() : source.stream()
                .filter(this::hasOhlc)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (rows.isEmpty()) {
            dataGaps.add("OHLC 日 K 数据缺失");
            return new ShortTermMomentumQuality(
                    scale(turnoverRate),
                    turnover.band(),
                    turnover.score(),
                    null,
                    null,
                    null,
                    "K线强度待补",
                    new BigDecimal("50.00"),
                    !latestBarCompleted,
                    false,
                    dataGaps
            );
        }

        int lastIndex = rows.size() - 1;
        EastMoneyKLine latest = latestBarCompleted
                ? rows.get(lastIndex)
                : effectiveLatest(rows.get(lastIndex), evaluationClose);
        CandleStrength latestStrength = candleStrength(latest);
        List<BigDecimal> recentBullishShadows = rows.subList(Math.max(0, rows.size() - 3), rows.size()).stream()
                .map(row -> row == rows.get(lastIndex) ? latest : row)
                .filter(this::isBullish)
                .map(this::candleStrength)
                .filter(strength -> strength.upperShadowPercent() != null)
                .map(CandleStrength::upperShadowPercent)
                .sorted()
                .toList();
        BigDecimal median = median(recentBullishShadows);
        if (median == null) {
            dataGaps.add("最近 3 个交易日没有可计算的上涨 K 线");
        }
        if (latestStrength.upperShadowPercent() == null || latestStrength.closeLocationPercent() == null) {
            dataGaps.add("最新 K 线振幅为零，无法计算收盘强度");
        }

        CloseStrengthEvaluation closeStrength = evaluateCloseStrength(
                median,
                latestStrength.upperShadowPercent(),
                latestStrength.closeLocationPercent()
        );
        return new ShortTermMomentumQuality(
                scale(turnoverRate),
                turnover.band(),
                turnover.score(),
                scale(latestStrength.upperShadowPercent()),
                scale(median),
                scale(latestStrength.closeLocationPercent()),
                closeStrength.label(),
                closeStrength.score(),
                !latestBarCompleted,
                closeStrength.extremeUpperShadow(),
                dataGaps
        );
    }

    private TurnoverEvaluation evaluateTurnover(EastMoneyQuote quote, BigDecimal turnoverRate) {
        if (turnoverRate == null) {
            return new TurnoverEvaluation("UNAVAILABLE", new BigDecimal("45.00"));
        }
        BigDecimal rate = turnoverRate.max(BigDecimal.ZERO);
        BigDecimal amount = quote == null ? null : quote.amount();
        boolean veryLargeAmount = amount != null && amount.compareTo(LARGE_AMOUNT) >= 0;
        if (veryLargeAmount && rate.compareTo(new BigDecimal("1.50")) >= 0 && rate.compareTo(FIVE) <= 0) {
            BigDecimal center = new BigDecimal("2.60");
            BigDecimal score = new BigDecimal("96").subtract(rate.subtract(center).abs().multiply(new BigDecimal("6")));
            return new TurnoverEvaluation("PREFERRED", scale(score.max(new BigDecimal("85"))));
        }
        if (rate.compareTo(TWO) >= 0 && rate.compareTo(FIVE) <= 0) {
            BigDecimal score = HUNDRED.subtract(rate.subtract(THREE).abs().multiply(new BigDecimal("8")));
            return new TurnoverEvaluation("PREFERRED", scale(score));
        }
        if (rate.compareTo(ONE) >= 0 && rate.compareTo(TWO) < 0) {
            BigDecimal score = new BigDecimal("55").add(rate.subtract(ONE).multiply(new BigDecimal("37")));
            return new TurnoverEvaluation("OBSERVATION", scale(score));
        }
        if (rate.compareTo(FIVE) > 0 && rate.compareTo(EIGHT) <= 0) {
            BigDecimal score = new BigDecimal("84").subtract(
                    rate.subtract(FIVE).multiply(new BigDecimal("9.6666667"))
            );
            return new TurnoverEvaluation("OBSERVATION", scale(score));
        }
        if (rate.compareTo(ONE) < 0) {
            return new TurnoverEvaluation(
                    "INSUFFICIENT",
                    scale(new BigDecimal("35").add(rate.multiply(new BigDecimal("20"))).min(new BigDecimal("54")))
            );
        }
        return new TurnoverEvaluation(
                "OVERHEATED",
                scale(new BigDecimal("35").subtract(rate.subtract(EIGHT).multiply(new BigDecimal("5")))
                        .max(new BigDecimal("20")))
        );
    }

    private CloseStrengthEvaluation evaluateCloseStrength(
            BigDecimal medianUpperShadow,
            BigDecimal latestUpperShadow,
            BigDecimal closeLocation
    ) {
        if (latestUpperShadow != null
                && latestUpperShadow.compareTo(new BigDecimal("50")) > 0
                && closeLocation != null
                && closeLocation.compareTo(new BigDecimal("60")) < 0) {
            return new CloseStrengthEvaluation("长上影观察", new BigDecimal("30.00"), true);
        }
        if (medianUpperShadow == null || closeLocation == null) {
            return new CloseStrengthEvaluation("K线强度待补", new BigDecimal("50.00"), false);
        }
        if (medianUpperShadow.compareTo(new BigDecimal("20")) <= 0
                && closeLocation.compareTo(new BigDecimal("75")) >= 0) {
            return new CloseStrengthEvaluation("上攻收盘强", new BigDecimal("95.00"), false);
        }
        if (medianUpperShadow.compareTo(new BigDecimal("20")) <= 0) {
            return new CloseStrengthEvaluation("上影短但收盘一般", new BigDecimal("82.00"), false);
        }
        if (medianUpperShadow.compareTo(new BigDecimal("35")) <= 0) {
            return new CloseStrengthEvaluation("上影中性", new BigDecimal("75.00"), false);
        }
        if (medianUpperShadow.compareTo(new BigDecimal("50")) <= 0) {
            return new CloseStrengthEvaluation("上方抛压偏强", new BigDecimal("55.00"), false);
        }
        return new CloseStrengthEvaluation("长上影观察", new BigDecimal("40.00"), true);
    }

    private EastMoneyKLine effectiveLatest(EastMoneyKLine row, BigDecimal evaluationClose) {
        if (evaluationClose == null || evaluationClose.compareTo(BigDecimal.ZERO) <= 0) {
            return row;
        }
        BigDecimal high = row.high() == null ? evaluationClose : row.high().max(evaluationClose);
        BigDecimal low = row.low() == null ? evaluationClose : row.low().min(evaluationClose);
        return new EastMoneyKLine(
                row.symbol(),
                row.tradeDate(),
                row.open(),
                evaluationClose,
                high,
                low,
                row.volume(),
                row.amount()
        );
    }

    private CandleStrength candleStrength(EastMoneyKLine row) {
        if (!hasOhlc(row)) {
            return new CandleStrength(null, null);
        }
        BigDecimal range = row.high().subtract(row.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return new CandleStrength(null, null);
        }
        BigDecimal upperShadow = row.high().subtract(row.open().max(row.close()))
                .max(BigDecimal.ZERO)
                .multiply(HUNDRED)
                .divide(range, 6, RoundingMode.HALF_UP);
        BigDecimal closeLocation = row.close().subtract(row.low())
                .max(BigDecimal.ZERO)
                .min(range)
                .multiply(HUNDRED)
                .divide(range, 6, RoundingMode.HALF_UP);
        return new CandleStrength(upperShadow, closeLocation);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return values.get(middle - 1).add(values.get(middle))
                .divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP);
    }

    private boolean hasOhlc(EastMoneyKLine row) {
        return row != null
                && row.tradeDate() != null
                && row.open() != null
                && row.close() != null
                && row.high() != null
                && row.low() != null;
    }

    private boolean isBullish(EastMoneyKLine row) {
        return hasOhlc(row) && row.close().compareTo(row.open()) > 0;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record TurnoverEvaluation(String band, BigDecimal score) {
    }

    private record CandleStrength(BigDecimal upperShadowPercent, BigDecimal closeLocationPercent) {
    }

    private record CloseStrengthEvaluation(
            String label,
            BigDecimal score,
            boolean extremeUpperShadow
    ) {
    }
}
