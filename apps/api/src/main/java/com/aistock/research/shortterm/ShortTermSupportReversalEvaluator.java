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
public class ShortTermSupportReversalEvaluator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_CHANGE = new BigDecimal("-2.00");
    private static final BigDecimal MAX_CHANGE = BigDecimal.ZERO;
    private static final BigDecimal MIN_LOWER_SHADOW = new BigDecimal("50");
    private static final BigDecimal MAX_BODY = new BigDecimal("35");
    private static final BigDecimal MAX_UPPER_SHADOW = new BigDecimal("20");
    private static final BigDecimal MIN_CLOSE_LOCATION = new BigDecimal("70");
    private static final BigDecimal SUPPORT_TOUCH_TOLERANCE = new BigDecimal("1.005");
    private static final BigDecimal MIN_MA20_SLOPE = new BigDecimal("-0.20");
    private static final BigDecimal MAX_DISTANCE_TO_MA20 = new BigDecimal("8.00");
    private static final BigDecimal MIN_VOLUME_OBSERVATION = new BigDecimal("0.80");
    private static final BigDecimal MIN_VOLUME_CONFIRMED = new BigDecimal("1.00");
    private static final BigDecimal MAX_VOLUME_CONFIRMED = new BigDecimal("2.50");
    private static final BigDecimal MIN_TURNOVER = new BigDecimal("1.00");
    private static final BigDecimal MAX_TURNOVER = new BigDecimal("8.00");

    public ShortTermSupportReversalSignal evaluate(
            EastMoneyQuote quote,
            List<EastMoneyKLine> source,
            BigDecimal evaluationClose,
            boolean latestBarCompleted,
            ShortTermTechnicalSnapshot technical,
            ShortTermMomentumQuality momentum
    ) {
        return evaluate(
                quote,
                source,
                evaluationClose,
                latestBarCompleted,
                technical,
                momentum,
                MAX_DISTANCE_TO_MA20
        );
    }

    public ShortTermSupportReversalSignal evaluate(
            EastMoneyQuote quote,
            List<EastMoneyKLine> source,
            BigDecimal evaluationClose,
            boolean latestBarCompleted,
            ShortTermTechnicalSnapshot technical,
            ShortTermMomentumQuality momentum,
            BigDecimal maxDistanceToMa20
    ) {
        List<String> dataGaps = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        EastMoneyKLine latest = latest(source, evaluationClose, latestBarCompleted);
        if (latest == null) {
            return ShortTermSupportReversalSignal.unavailable();
        }

        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(ZERO) <= 0) {
            dataGaps.add("最新 K 线振幅为零，无法识别长下影承接");
            return signal(
                    "UNAVAILABLE", "长下影承接待复核", ZERO,
                    null, null, null, null, Support.none(),
                    false, false, false, false, !latestBarCompleted,
                    reasons, dataGaps
            );
        }

        BigDecimal lowerShadow = percent(latest.open().min(latest.close()).subtract(latest.low()).max(ZERO), range);
        BigDecimal body = percent(latest.close().subtract(latest.open()).abs(), range);
        BigDecimal upperShadow = percent(latest.high().subtract(latest.open().max(latest.close())).max(ZERO), range);
        BigDecimal closeLocation = percent(latest.close().subtract(latest.low()).max(ZERO).min(range), range);

        BigDecimal change = quote == null ? null : quote.changePercent();
        boolean declineQualified = change != null
                && change.compareTo(MIN_CHANGE) >= 0
                && change.compareTo(MAX_CHANGE) <= 0;
        if (change == null) {
            dataGaps.add("实时涨跌幅缺失");
        } else if (!declineQualified) {
            reasons.add("当日跌幅不在 -2% 至 0% 的承接观察区间");
        }

        boolean shapeQualified = lowerShadow.compareTo(MIN_LOWER_SHADOW) >= 0
                && body.compareTo(MAX_BODY) <= 0
                && upperShadow.compareTo(MAX_UPPER_SHADOW) <= 0
                && closeLocation.compareTo(MIN_CLOSE_LOCATION) >= 0;
        if (lowerShadow.compareTo(MIN_LOWER_SHADOW) < 0) {
            reasons.add("下影线占比不足 50%");
        }
        if (body.compareTo(MAX_BODY) > 0) {
            reasons.add("实体占比超过 35%");
        }
        if (upperShadow.compareTo(MAX_UPPER_SHADOW) > 0) {
            reasons.add("上影线占比超过 20%");
        }
        if (closeLocation.compareTo(MIN_CLOSE_LOCATION) < 0) {
            reasons.add("收盘位置低于日内振幅的 70%");
        }

        Support support = support(latest, technical);
        if (!support.reclaimed()) {
            reasons.add("未触及并收复 MA5、MA10、MA20 或前 20 日高点");
        }

        boolean trendQualified = trendQualified(latest.close(), technical, maxDistanceToMa20);
        if (!trendQualified) {
            reasons.add("MA20 趋势或价格位置不满足承接反转条件");
        }

        BigDecimal volumeRatio = technical == null ? null : technical.volumeRatio20();
        boolean volumeQualified = between(volumeRatio, MIN_VOLUME_CONFIRMED, MAX_VOLUME_CONFIRMED);
        boolean observationVolume = volumeRatio != null
                && volumeRatio.compareTo(MIN_VOLUME_OBSERVATION) >= 0
                && volumeRatio.compareTo(MIN_VOLUME_CONFIRMED) < 0;
        if (volumeRatio == null) {
            dataGaps.add("20 日量比缺失");
        } else if (!volumeQualified && !observationVolume) {
            reasons.add("20 日量比不在 0.80 至 2.50 的承接验证区间");
        }

        BigDecimal turnover = momentum != null && momentum.turnoverRatePercent() != null
                ? momentum.turnoverRatePercent()
                : quote == null ? null : quote.turnoverRate();
        boolean turnoverQualified = between(turnover, MIN_TURNOVER, MAX_TURNOVER);
        if (turnover == null) {
            dataGaps.add("实时换手率缺失");
        } else if (!turnoverQualified) {
            reasons.add("换手率不在 1% 至 8% 的可交易区间");
        }

        boolean coreQualified = declineQualified
                && shapeQualified
                && support.reclaimed()
                && trendQualified
                && turnoverQualified;
        String state = coreQualified && volumeQualified
                ? "CONFIRMED"
                : coreQualified && observationVolume ? "OBSERVATION" : "NONE";
        String label = switch (state) {
            case "CONFIRMED" -> "长下影承接确认";
            case "OBSERVATION" -> "长下影承接观察";
            default -> "长下影承接未确认";
        };
        BigDecimal score = score(
                support.reclaimed(), lowerShadow, body, upperShadow, closeLocation,
                volumeQualified, observationVolume, turnoverQualified, trendQualified,
                technical
        );

        return signal(
                state, label, score, lowerShadow, body, upperShadow, closeLocation,
                support, support.reclaimed(), trendQualified, volumeQualified,
                turnoverQualified, !latestBarCompleted, reasons, dataGaps
        );
    }

    private BigDecimal score(
            boolean supportReclaimed,
            BigDecimal lowerShadow,
            BigDecimal body,
            BigDecimal upperShadow,
            BigDecimal closeLocation,
            boolean volumeQualified,
            boolean observationVolume,
            boolean turnoverQualified,
            boolean trendQualified,
            ShortTermTechnicalSnapshot technical
    ) {
        BigDecimal score = supportReclaimed ? new BigDecimal("35") : ZERO;
        if (lowerShadow.compareTo(MIN_LOWER_SHADOW) >= 0) {
            score = score.add(new BigDecimal("10"));
        }
        if (closeLocation.compareTo(MIN_CLOSE_LOCATION) >= 0) {
            score = score.add(new BigDecimal("8"));
        }
        if (upperShadow.compareTo(MAX_UPPER_SHADOW) <= 0) {
            score = score.add(new BigDecimal("6"));
        }
        if (body.compareTo(MAX_BODY) <= 0) {
            score = score.add(new BigDecimal("6"));
        }
        if (volumeQualified) {
            score = score.add(new BigDecimal("12"));
        } else if (observationVolume) {
            score = score.add(new BigDecimal("8"));
        }
        if (turnoverQualified) {
            score = score.add(new BigDecimal("8"));
        }
        if (trendQualified) {
            score = score.add(new BigDecimal("10"));
        }
        if (technical != null
                && technical.goldenCross() != null
                && !"NONE".equals(technical.goldenCross().state())
                && !"UNAVAILABLE".equals(technical.goldenCross().state())) {
            score = score.add(new BigDecimal("5"));
        }
        return scale(score.min(HUNDRED));
    }

    private Support support(EastMoneyKLine latest, ShortTermTechnicalSnapshot technical) {
        if (technical == null) {
            return Support.none();
        }
        return List.of(
                        new Support("MA5", technical.ma5(), false),
                        new Support("MA10", technical.ma10(), false),
                        new Support("MA20", technical.ma20(), false),
                        new Support("PREVIOUS_HIGH20", technical.previousHigh20(), false)
                ).stream()
                .filter(candidate -> candidate.price() != null && candidate.price().compareTo(ZERO) > 0)
                .filter(candidate -> latest.low().compareTo(candidate.price().multiply(SUPPORT_TOUCH_TOLERANCE)) <= 0)
                .filter(candidate -> latest.close().compareTo(candidate.price()) >= 0)
                .max(Comparator.comparing(Support::price))
                .map(candidate -> new Support(candidate.type(), scale(candidate.price()), true))
                .orElseGet(Support::none);
    }

    private boolean trendQualified(
            BigDecimal close,
            ShortTermTechnicalSnapshot technical,
            BigDecimal configuredMaxDistance
    ) {
        if (technical == null
                || technical.ma20() == null
                || technical.ma20SlopePercent() == null
                || technical.distanceToMa20Percent() == null) {
            return false;
        }
        BigDecimal maxDistance = configuredMaxDistance == null || configuredMaxDistance.compareTo(ZERO) <= 0
                ? MAX_DISTANCE_TO_MA20
                : configuredMaxDistance;
        return close.compareTo(technical.ma20()) >= 0
                && technical.distanceToMa20Percent().compareTo(ZERO) >= 0
                && technical.distanceToMa20Percent().compareTo(maxDistance) <= 0
                && technical.ma20SlopePercent().compareTo(MIN_MA20_SLOPE) >= 0;
    }

    private EastMoneyKLine latest(List<EastMoneyKLine> source, BigDecimal evaluationClose, boolean completed) {
        if (source == null) {
            return null;
        }
        List<EastMoneyKLine> rows = source.stream()
                .filter(this::hasOhlc)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        if (completed || evaluationClose == null || evaluationClose.compareTo(ZERO) <= 0) {
            return latest;
        }
        return new EastMoneyKLine(
                latest.symbol(),
                latest.tradeDate(),
                latest.open(),
                evaluationClose,
                latest.high().max(evaluationClose),
                latest.low().min(evaluationClose),
                latest.volume(),
                latest.amount(),
                latest.turnoverRate()
        );
    }

    private ShortTermSupportReversalSignal signal(
            String state,
            String label,
            BigDecimal score,
            BigDecimal lowerShadow,
            BigDecimal body,
            BigDecimal upperShadow,
            BigDecimal closeLocation,
            Support support,
            boolean supportReclaimed,
            boolean trendQualified,
            boolean volumeQualified,
            boolean turnoverQualified,
            boolean provisional,
            List<String> reasons,
            List<String> dataGaps
    ) {
        return new ShortTermSupportReversalSignal(
                state,
                label,
                scale(score),
                scale(lowerShadow),
                scale(body),
                scale(upperShadow),
                scale(closeLocation),
                support == null ? null : support.type(),
                support == null ? null : support.price(),
                supportReclaimed,
                trendQualified,
                volumeQualified,
                turnoverQualified,
                provisional,
                reasons,
                dataGaps
        );
    }

    private boolean between(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private BigDecimal percent(BigDecimal value, BigDecimal denominator) {
        return value.multiply(HUNDRED).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private boolean hasOhlc(EastMoneyKLine row) {
        return row != null
                && row.tradeDate() != null
                && row.open() != null
                && row.close() != null
                && row.high() != null
                && row.low() != null;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Support(String type, BigDecimal price, boolean reclaimed) {
        private static Support none() {
            return new Support(null, null, false);
        }
    }
}
