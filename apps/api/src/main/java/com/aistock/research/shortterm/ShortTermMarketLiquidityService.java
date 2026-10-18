package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class ShortTermMarketLiquidityService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime MORNING_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_FIRST = LocalTime.of(13, 1);
    private static final LocalTime CLOSE = LocalTime.of(15, 0);
    private static final String SOURCE = "东方财富两日指数分时：上证指数+深证综指";
    private static final String SAMPLE_EXPLANATION = "沪深A股有效报价样本成交额按报价截止时间汇总；指数成交额（元）与成交量（手）按比较时刻累计，二者范围与时间口径分别展示。";
    private final EastMoneyClient client;
    private final TradingClockService clock;

    public ShortTermMarketLiquidityService(EastMoneyClient client, TradingClockService clock) {
        this.client = client;
        this.clock = clock;
    }

    public ShortTermMarketLiquidity snapshot(List<EastMoneyQuote> source, ShortTermCoverageSnapshot coverage,
            LocalDateTime decisionAt) {
        List<String> gaps = new ArrayList<>();
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        if (source != null) source.stream().filter(Objects::nonNull)
                .filter(q -> q.symbol() != null && q.symbol().matches("[036]\\d{5}"))
                .forEach(q -> unique.putIfAbsent(q.symbol(), q));
        List<EastMoneyQuote> quotes = List.copyOf(unique.values());
        Instant decisionInstant = decisionAt == null ? null : decisionAt.atZone(SHANGHAI).toInstant();
        List<EastMoneyQuote> dated = quotes.stream().filter(q -> validTimestamp(q, decisionInstant)).toList();
        List<LocalDate> dates = dated.stream().map(EastMoneyQuote::tradeDate).distinct().toList();
        LocalDate tradeDate = dates.size() == 1 ? dates.get(0) : null;
        Instant watermark = dated.stream().map(EastMoneyQuote::marketTimestamp).max(Instant::compareTo).orElse(null);
        List<EastMoneyQuote> amounts = dated.stream().filter(q -> nonnegative(q.amount())).toList();
        BigDecimal amountCoverage = quotes.isEmpty() ? BigDecimal.ZERO
                : percent(BigDecimal.valueOf(amounts.size()), BigDecimal.valueOf(quotes.size()));
        BigDecimal sampleAmount = amountCoverage.compareTo(new BigDecimal("95")) >= 0 && tradeDate != null
                ? amounts.stream().map(EastMoneyQuote::amount).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        if (sampleAmount == null) gaps.add("沪深A股样本成交额有效覆盖不足95%或日期无法核实，未将缺失金额记为0");
        if (decisionAt == null || tradeDate == null || dated.size() != quotes.size()) {
            gaps.add("行情日或市场时间戳缺失、混合或晚于决策时间");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, null, gaps);
        }
        LocalDate expectedQuoteDate = clock.isMarketClosedDay(decisionAt.toLocalDate())
                ? clock.previousTradingDay(decisionAt.toLocalDate()) : decisionAt.toLocalDate();
        if (!tradeDate.equals(expectedQuoteDate)) {
            gaps.add("行情日 " + tradeDate + " 与本次决策所需行情日 " + expectedQuoteDate + " 不一致");
            return unavailable(tradeDate, watermark, null, amountCoverage, null, gaps);
        }
        LocalDate previous = clock.previousTradingDay(tradeDate);
        if (clock.isMarketClosedDay(tradeDate)
                || !clock.verifiedTradingDayAfter(previous, 1).filter(tradeDate::equals).isPresent()) {
            gaps.add("前一交易日历未核实，不使用自然日或不明历史日替代");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }
        if (coverage == null || !coverage.executionReliable() || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(new BigDecimal("0.95")) < 0) {
            gaps.add("有效行情覆盖未通过95%，量额上下文仅作缺口展示");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }

        LocalTime decisionMinute = tradeDate.isBefore(decisionAt.toLocalDate()) ? CLOSE
                : decisionAt.toLocalTime().truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        LocalTime quoteMinute = watermark.atZone(SHANGHAI).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        LocalTime target = sessionMinute(decisionMinute.isBefore(quoteMinute) ? decisionMinute : quoteMinute);
        if (target.isBefore(OPEN)) {
            gaps.add("尚无已完成的正常交易分钟");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }
        boolean closedExpected = tradeDate.isBefore(decisionAt.toLocalDate())
                || !decisionAt.toLocalTime().isBefore(CLOSE.plusMinutes(1));
        if (closedExpected && !target.equals(CLOSE)) {
            gaps.add("收盘量额仍缺少15:00行情时间戳，不能用盘中快照充当全天数据");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }
        List<Map<LocalTime, EastMoneyIntradayPoint>> grids = new ArrayList<>();
        for (String index : List.of("sh000001", "sz399106")) {
            List<EastMoneyIntradayPoint> points;
            try {
                points = client.fetchIndexIntradayTrends(index);
            } catch (RuntimeException exception) {
                gaps.add(index + " 两日分时源暂不可用");
                return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
            }
            for (LocalDate date : List.of(tradeDate, previous)) {
                Map<LocalTime, EastMoneyIntradayPoint> grid = grid(points, date, target, index, gaps);
                if (grid == null || grid.isEmpty()) {
                    if (grid != null) gaps.add(index + " 缺少" + (date.equals(previous) ? "已核实前一交易日 " : "行情日 ") + date + " 的分钟数据");
                    return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
                }
                grids.add(grid);
            }
        }
        LocalTime commonCutoff = grids.stream().map(grid -> grid.keySet().stream().max(LocalTime::compareTo).orElse(OPEN))
                .min(LocalTime::compareTo).orElse(OPEN);
        LocalTime freshnessReference = tradeDate.isBefore(decisionAt.toLocalDate()) ? CLOSE : decisionAt.toLocalTime();
        // No afternoon minute can be complete until 13:02; the last completed morning minute remains current during lunch.
        if (freshnessReference.isAfter(MORNING_CLOSE) && freshnessReference.isBefore(AFTERNOON_FIRST.plusMinutes(1))) {
            freshnessReference = MORNING_CLOSE;
        }
        if (freshnessReference.isAfter(CLOSE)) freshnessReference = CLOSE;
        if (ChronoUnit.SECONDS.between(commonCutoff, freshnessReference) > 180
                || (closedExpected && !commonCutoff.equals(CLOSE))) {
            gaps.add("指数分时或报价截止时间滞后，缺少当前可比较的完整分钟");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }
        List<LocalTime> expectedMinutes = minutesThrough(commonCutoff);
        for (Map<LocalTime, EastMoneyIntradayPoint> grid : grids) {
            if (!grid.keySet().containsAll(expectedMinutes)) {
                gaps.add("两市两日分钟覆盖不完整：必须从09:30覆盖到同一截止时刻，午休除外");
                return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
            }
        }
        BigDecimal currentAmount = total(grids.get(0), expectedMinutes, true).add(total(grids.get(2), expectedMinutes, true));
        BigDecimal previousAmount = total(grids.get(1), expectedMinutes, true).add(total(grids.get(3), expectedMinutes, true));
        BigDecimal currentVolume = total(grids.get(0), expectedMinutes, false).add(total(grids.get(2), expectedMinutes, false));
        BigDecimal previousVolume = total(grids.get(1), expectedMinutes, false).add(total(grids.get(3), expectedMinutes, false));
        if (previousAmount.signum() <= 0 || previousVolume.signum() <= 0) {
            gaps.add("前一交易日同刻成交量或成交额为0，不能计算变化率");
            return unavailable(tradeDate, watermark, sampleAmount, amountCoverage, previous, gaps);
        }
        BigDecimal amountChange = change(currentAmount, previousAmount);
        BigDecimal volumeChange = change(currentVolume, previousVolume);
        String status = commonCutoff.equals(CLOSE) && closedExpected ? "CLOSE" : "SAME_TIME";
        String label = amountChange.compareTo(new BigDecimal("10")) >= 0 ? "同刻成交额放大"
                : amountChange.compareTo(new BigDecimal("-10")) <= 0 ? "同刻成交额收缩" : "同刻成交额平稳";
        return new ShortTermMarketLiquidity(tradeDate, watermark, status, label, sampleAmount, amountCoverage,
                currentAmount, previousAmount, amountChange, currentVolume, previousVolume, volumeChange,
                previous, commonCutoff.toString(), SOURCE,
                SAMPLE_EXPLANATION + " 两市两日完整分钟均累计至" + commonCutoff + "；基准日为已核实前一交易日" + previous + "。", gaps);
    }

    private boolean validTimestamp(EastMoneyQuote quote, Instant decision) {
        return decision != null && quote.tradeDate() != null && quote.marketTimestamp() != null
                && !quote.marketTimestamp().isAfter(decision)
                && quote.tradeDate().equals(quote.marketTimestamp().atZone(SHANGHAI).toLocalDate());
    }

    private Map<LocalTime, EastMoneyIntradayPoint> grid(List<EastMoneyIntradayPoint> points, LocalDate date,
            LocalTime cutoff, String index, List<String> gaps) {
        Map<LocalTime, EastMoneyIntradayPoint> result = new TreeMap<>();
        if (points == null) return result;
        for (EastMoneyIntradayPoint point : points) {
            if (point == null || point.minute() == null || !point.minute().toLocalDate().equals(date)) continue;
            LocalTime minute = point.minute().toLocalTime();
            if (minute.isAfter(cutoff)) continue; // Future/current unfinished minutes never contribute.
            if (!minute.equals(minute.truncatedTo(ChronoUnit.MINUTES)) || !isSessionMinute(minute)) {
                gaps.add(index + " 含非正常交易分钟，量额口径无法核实");
                return null;
            }
            if (!nonnegative(point.amount()) || !nonnegative(point.volume())) {
                gaps.add(index + " 分钟成交量/成交额缺失或出现负值");
                return null;
            }
            EastMoneyIntradayPoint previous = result.putIfAbsent(minute, point);
            if (previous != null && (previous.amount().compareTo(point.amount()) != 0
                    || previous.volume().compareTo(point.volume()) != 0)) {
                gaps.add(index + " 同一分钟存在冲突量额，拒绝重复累计");
                return null;
            }
        }
        return result;
    }

    private LocalTime sessionMinute(LocalTime minute) {
        if (minute.isAfter(CLOSE)) return CLOSE;
        if (minute.isAfter(MORNING_CLOSE) && minute.isBefore(AFTERNOON_FIRST)) return MORNING_CLOSE;
        return minute;
    }

    private boolean isSessionMinute(LocalTime minute) {
        return !minute.isBefore(OPEN) && !minute.isAfter(MORNING_CLOSE)
                || !minute.isBefore(AFTERNOON_FIRST) && !minute.isAfter(CLOSE);
    }

    private List<LocalTime> minutesThrough(LocalTime cutoff) {
        List<LocalTime> result = new ArrayList<>();
        for (LocalTime minute = OPEN; !minute.isAfter(cutoff); minute = minute.plusMinutes(1)) {
            if (isSessionMinute(minute)) result.add(minute);
        }
        return result;
    }

    private BigDecimal total(Map<LocalTime, EastMoneyIntradayPoint> grid, List<LocalTime> minutes, boolean amount) {
        return minutes.stream().map(grid::get).map(p -> amount ? p.amount() : p.volume())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean nonnegative(BigDecimal value) { return value != null && value.signum() >= 0; }
    private BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.multiply(new BigDecimal("100")).divide(base, 2, RoundingMode.HALF_UP);
    }
    private BigDecimal change(BigDecimal value, BigDecimal base) { return percent(value.subtract(base), base); }

    private ShortTermMarketLiquidity unavailable(LocalDate date, Instant cutoff, BigDecimal sampleAmount,
            BigDecimal amountCoverage, LocalDate previous, List<String> gaps) {
        return new ShortTermMarketLiquidity(date, cutoff, "UNAVAILABLE", "同刻量额待补", sampleAmount, amountCoverage,
                null, null, null, null, null, null, previous, null, SOURCE,
                SAMPLE_EXPLANATION + " 同刻比较证据不足，不解释为缩量。", gaps);
    }
}
