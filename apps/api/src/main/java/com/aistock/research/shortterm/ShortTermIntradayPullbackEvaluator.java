package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

@Component
public class ShortTermIntradayPullbackEvaluator {
    // Initial engineering thresholds; these do not establish a profitable strategy.
    private static final BigDecimal PLATFORM_WIDTH = new BigDecimal("1.015");
    private static final BigDecimal AVERAGE_TOUCH = new BigDecimal("1.003");
    private static final BigDecimal PULLBACK_VOLUME = new BigDecimal("0.8");
    private static final BigDecimal BREAKOUT_VOLUME = new BigDecimal("1.5");

    public ShortTermIntradayPullbackSignal evaluate(
            List<EastMoneyIntradayPoint> points, LocalDate tradeDate, LocalDateTime decisionAt) {
        if (tradeDate == null || decisionAt == null || !tradeDate.equals(decisionAt.toLocalDate())) {
            return unavailable("交易日或判定时间缺失、不一致");
        }
        LocalDateTime start = tradeDate.atTime(13, 1);
        LocalDateTime cutoff = decisionAt.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime end = cutoff.minusMinutes(1).isBefore(tradeDate.atTime(15, 0))
                ? cutoff.minusMinutes(1) : tradeDate.atTime(15, 0);
        if (points == null || points.isEmpty() || end.isBefore(start)) {
            return unavailable("缺少当日13:01后的已完成真实分钟OHLCV");
        }

        TreeMap<LocalDateTime, EastMoneyIntradayPoint> minutes = new TreeMap<>();
        for (EastMoneyIntradayPoint point : points) {
            if (point == null || point.minute() == null) return unavailable("分钟时间缺失");
            LocalDateTime minute = point.minute();
            // Filter excluded data before checking values, so future/other-day rows cannot affect a signal.
            if (!tradeDate.equals(minute.toLocalDate()) || minute.isBefore(start)
                    || minute.isAfter(tradeDate.atTime(15, 0)) || !minute.isBefore(cutoff)) continue;
            if (minute.getSecond() != 0 || minute.getNano() != 0) return unavailable("分钟时间未对齐：" + minute);
            String gap = invalidFields(point);
            if (gap != null) return unavailable(minute.toLocalTime() + " " + gap);
            EastMoneyIntradayPoint previous = minutes.putIfAbsent(minute, point);
            if (previous != null && !sameMinute(previous, point)) return unavailable("重复分钟数据冲突：" + minute.toLocalTime());
        }
        if (minutes.isEmpty()) return unavailable("缺少当日13:01后的已完成真实分钟OHLCV");
        String symbol = minutes.firstEntry().getValue().symbol();
        for (LocalDateTime minute = start; !minute.isAfter(end); minute = minute.plusMinutes(1)) {
            EastMoneyIntradayPoint point = minutes.get(minute);
            if (point == null) return unavailable("分钟缺失：" + minute.toLocalTime() + "，无法确认连续10分钟K及后续守位");
            if (!Objects.equals(symbol, point.symbol())) return unavailable("分钟数据混入不同股票");
        }

        List<EastMoneyIntradayPoint> rows = List.copyOf(minutes.values());
        List<Bar> bars = new ArrayList<>();
        for (int offset = 0; offset + 10 <= rows.size(); offset += 10) {
            bars.add(aggregate(rows.subList(offset, offset + 10)));
        }
        if (bars.size() < 3) return watch("至少需要平台两根与回踩一根完整10分钟K，继续观察");
        ShortTermIntradayPullbackSignal latestWatch = null;
        for (int i = bars.size() - 1; i >= 2; i--) {
            ShortTermIntradayPullbackSignal candidate = evaluateCandidate(bars, i, rows);
            if (candidate == null) continue;
            if (candidate.confirmed()) return candidate;
            if (latestWatch == null) latestWatch = candidate;
        }
        return latestWatch == null
                ? watch("尚未形成连续两根窄平台后缩量回踩当日均价线的顺序，继续观察") : latestWatch;
    }

    private ShortTermIntradayPullbackSignal evaluateCandidate(List<Bar> bars, int index,
                                                             List<EastMoneyIntradayPoint> rows) {
        Bar first = bars.get(index - 2);
        Bar second = bars.get(index - 1);
        Bar pullback = bars.get(index);
        BigDecimal platformHigh = first.high().max(second.high());
        BigDecimal platformLow = first.low().min(second.low());
        if (platformHigh.compareTo(platformLow.multiply(PLATFORM_WIDTH)) > 0
                || pullback.close().compareTo(second.close()) >= 0
                || !holdsAverage(pullback.points())
                || pullback.points().stream().noneMatch(point ->
                point.low().compareTo(point.averagePrice().multiply(AVERAGE_TOUCH)) <= 0)) return null;

        BigDecimal platformVolume = first.volume().add(second.volume());
        if (platformVolume.signum() <= 0) return unavailable("平台成交量基数为零，无法计算回踩量比");
        // Cross multiplication keeps threshold checks exact; ratios are rounded only for presentation.
        if (pullback.volume().multiply(BigDecimal.valueOf(2))
                .compareTo(platformVolume.multiply(PULLBACK_VOLUME)) > 0) return null;
        BigDecimal pullbackRatio = ratio(pullback.volume().multiply(BigDecimal.valueOf(2)), platformVolume);
        BigDecimal breakoutBase = platformVolume.add(pullback.volume());
        List<String> reasons = new ArrayList<>();
        reasons.add(pullback.end().toLocalTime() + " 缩量回踩：量比" + pullbackRatio
                + "（≤0.80），低点" + price(pullback.low()) + "，平台高点" + price(platformHigh));
        for (int next = index + 1; next < bars.size() && next <= index + 3; next++) {
            Bar breakout = bars.get(next);
            if (!holdsAverage(breakout.points()) || breakout.low().compareTo(pullback.low()) < 0) {
                reasons.add(breakout.end().toLocalTime() + " 前后分钟已失守均价线或回踩低点，回踩失效");
                return signal("WATCH", pullback, null, platformHigh, pullbackRatio, null, reasons);
            }
            if (breakout.close().compareTo(platformHigh) <= 0) continue;
            BigDecimal breakoutRatio = ratio(breakout.volume().multiply(BigDecimal.valueOf(3)), breakoutBase);
            if (breakout.volume().multiply(BigDecimal.valueOf(3))
                    .compareTo(breakoutBase.multiply(BREAKOUT_VOLUME)) < 0) {
                reasons.add(breakout.end().toLocalTime() + " 收盘" + price(breakout.close())
                        + " 越过平台，但量比" + breakoutRatio + "不足1.50");
                continue;
            }
            List<EastMoneyIntradayPoint> afterBreakout = rows.stream()
                    .filter(point -> point.minute().isAfter(breakout.end())).toList();
            EastMoneyIntradayPoint latest = rows.get(rows.size() - 1);
            if (!holdsAverage(afterBreakout) || afterBreakout.stream()
                    .anyMatch(point -> point.low().compareTo(pullback.low()) < 0)) {
                reasons.add("突破后分钟最低价失守当时均价线或回踩低点，回踩确认已失效");
                return signal("WATCH", pullback, null, platformHigh, pullbackRatio, null, reasons);
            }
            if (latest.close().compareTo(platformHigh) <= 0 || latest.close().compareTo(latest.averagePrice()) <= 0) {
                reasons.add(latest.minute().toLocalTime() + " 最新已完成分钟收盘" + price(latest.close())
                        + "未站稳平台高点及均价线上方");
                return signal("WATCH", pullback, null, platformHigh, pullbackRatio, null, reasons);
            }
            reasons.add(breakout.end().toLocalTime() + " 放量突破：收盘" + price(breakout.close())
                    + "，量比" + breakoutRatio + "（≥1.50），发生于回踩后第" + (next - index) + "根10分钟K");
            reasons.add(latest.minute().toLocalTime() + " 最新收盘" + price(latest.close())
                    + "，均价线" + price(latest.averagePrice()) + "，突破后未失守均价线或回踩低点");
            return signal("CONFIRMED", pullback, breakout, platformHigh, pullbackRatio, breakoutRatio, reasons);
        }
        // An unfinished ten-minute candle cannot confirm a breakout, but its completed minutes can invalidate it.
        if (rows.stream().filter(point -> point.minute().isAfter(pullback.end()))
                .anyMatch(point -> point.low().compareTo(point.averagePrice()) < 0
                        || point.low().compareTo(pullback.low()) < 0)) {
            reasons.add("回踩后已完成分钟失守均价线或回踩低点，继续观察");
        } else {
            reasons.add(index + 3 < bars.size() ? "回踩后3根完整10分钟K内未获放量突破，窗口已过"
                    : "等待回踩后1—3根完整10分钟K收盘放量突破");
        }
        return signal("WATCH", pullback, null, platformHigh, pullbackRatio, null, reasons);
    }

    private Bar aggregate(List<EastMoneyIntradayPoint> points) {
        BigDecimal high = points.get(0).high();
        BigDecimal low = points.get(0).low();
        BigDecimal volume = BigDecimal.ZERO;
        for (EastMoneyIntradayPoint point : points) {
            high = high.max(point.high());
            low = low.min(point.low());
            volume = volume.add(point.volume());
        }
        EastMoneyIntradayPoint last = points.get(points.size() - 1);
        return new Bar(last.minute(), high, low, last.close(), volume, points);
    }

    private boolean holdsAverage(List<EastMoneyIntradayPoint> points) {
        return points.stream().allMatch(point -> point.low().compareTo(point.averagePrice()) >= 0);
    }

    private String invalidFields(EastMoneyIntradayPoint point) {
        if (point.volume() == null || point.volume().signum() < 0) return "成交量缺失或无效";
        if (!positive(point.averagePrice())) return "当日成交均价缺失或无效";
        if (!positive(point.open()) || !positive(point.close()) || !positive(point.high()) || !positive(point.low())) {
            return "真实分钟OHLC缺失或无效";
        }
        if (point.high().compareTo(point.open().max(point.close())) < 0
                || point.low().compareTo(point.open().min(point.close())) > 0) return "真实分钟OHLC高低价冲突";
        return null;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean sameMinute(EastMoneyIntradayPoint left, EastMoneyIntradayPoint right) {
        return Objects.equals(left.symbol(), right.symbol())
                && left.open().compareTo(right.open()) == 0 && left.close().compareTo(right.close()) == 0
                && left.high().compareTo(right.high()) == 0 && left.low().compareTo(right.low()) == 0
                && left.volume().compareTo(right.volume()) == 0 && left.averagePrice().compareTo(right.averagePrice()) == 0
                && (left.amount() == null ? right.amount() == null
                : right.amount() != null && left.amount().compareTo(right.amount()) == 0);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private String price(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private ShortTermIntradayPullbackSignal signal(String status, Bar pullback, Bar breakout,
                                                  BigDecimal platformHigh, BigDecimal pullbackRatio,
                                                  BigDecimal breakoutRatio, List<String> reasons) {
        return new ShortTermIntradayPullbackSignal(status,
                "CONFIRMED".equals(status) ? "分时缩量回踩后放量突破确认" : "分时回踩待确认",
                pullback.end(), breakout == null ? null : breakout.end(), platformHigh, pullback.low(),
                pullbackRatio, breakoutRatio, reasons, List.of());
    }

    private ShortTermIntradayPullbackSignal unavailable(String gap) {
        return new ShortTermIntradayPullbackSignal("UNAVAILABLE", "分时回踩数据不足", null, null,
                null, null, null, null, List.of(), List.of(gap));
    }

    private ShortTermIntradayPullbackSignal watch(String reason) {
        return new ShortTermIntradayPullbackSignal("WATCH", "分时回踩待确认", null, null,
                null, null, null, null, List.of(reason), List.of());
    }

    private record Bar(LocalDateTime end, BigDecimal high, BigDecimal low, BigDecimal close,
                       BigDecimal volume, List<EastMoneyIntradayPoint> points) { }
}
