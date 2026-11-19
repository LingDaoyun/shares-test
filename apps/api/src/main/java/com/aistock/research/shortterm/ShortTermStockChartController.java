package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardBar;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyKLineSeries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

@RestController
@RequestMapping("/api/short-term/stocks")
public class ShortTermStockChartController {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private final EastMoneyClient eastMoneyClient;

    public ShortTermStockChartController(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    @GetMapping("/{symbol}/intraday")
    public ShortTermStockIntradaySnapshot intraday(@PathVariable String symbol) {
        requireSymbol(symbol);
        ShortTermStockIntradaySnapshot primary = null;
        IllegalStateException eastMoneyFailure = null;
        try {
            primary = intradaySnapshot(symbol, eastMoneyClient.fetchIntradayOhlcv(symbol), Instant.now(), "东方财富");
            if (!primary.points().isEmpty()) {
                return primary;
            }
        } catch (IllegalStateException exception) {
            eastMoneyFailure = exception;
        }
        try {
            var backup = intradaySnapshot(symbol, eastMoneyClient.fetchSinaIntradayTrends(symbol), Instant.now(), "新浪");
            if (!backup.points().isEmpty()) {
                return backup;
            }
        } catch (CancellationException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            if (eastMoneyFailure != null) {
                eastMoneyFailure.addSuppressed(exception);
            }
        }
        if (eastMoneyFailure != null) {
            throw eastMoneyFailure;
        }
        return primary;
    }

    @GetMapping("/{symbol}/daily")
    public ShortTermStockDailySnapshot daily(@PathVariable String symbol) {
        requireSymbol(symbol);
        LocalDate today = LocalDate.now(MARKET_ZONE);
        EastMoneyKLineSeries series = fetchDailyChartSeries(symbol, today);
        return dailySnapshot(symbol, series, Instant.now());
    }

    EastMoneyKLineSeries fetchDailyChartSeries(String symbol, LocalDate today) {
        LocalDate begin = today.minusYears(1);
        EastMoneyKLineSeries series = eastMoneyClient.fetchDailyKLineSeries(symbol, begin, today);
        if (series == null || series.complete()) return series;
        List<LocalDate> availableDates = series.rows().stream()
                .filter(row -> row != null && symbol.equals(row.symbol()) && row.tradeDate() != null
                        && !row.tradeDate().isAfter(today))
                .map(EastMoneyKLine::tradeDate).distinct().sorted().toList();
        if (availableDates.isEmpty()) return series;
        LocalDate windowStart = availableDates.get(Math.max(0, availableDates.size() - 120));
        if (!windowStart.isAfter(begin)) return series;
        // Partial rows only locate a smaller query window; only a fresh complete series may be displayed.
        return eastMoneyClient.fetchDailyKLineSeries(symbol, windowStart, today);
    }

    static ShortTermStockIntradaySnapshot intradaySnapshot(
            String symbol, List<EastMoneyIntradayPoint> rows, Instant fetchedAt, String source
    ) {
        LocalDateTime now = LocalDateTime.ofInstant(fetchedAt, MARKET_ZONE);
        Map<LocalDateTime, ShortTermStockIntradaySnapshot.Point> byMinute = new TreeMap<>();
        if (rows != null) for (EastMoneyIntradayPoint row : rows) {
            if (row == null || !symbol.equals(row.symbol()) || row.minute() == null
                    || row.minute().isAfter(now) || !tradingMinute(row.minute().toLocalTime())
                    || !positive(row.close())) continue;
            byMinute.putIfAbsent(row.minute(), new ShortTermStockIntradaySnapshot.Point(
                    row.minute(), row.close(), positive(row.averagePrice()) ? row.averagePrice() : null,
                    nonNegative(row.volume()), nonNegative(row.amount())));
        }
        LocalDate tradeDate = byMinute.keySet().stream().map(LocalDateTime::toLocalDate)
                .max(LocalDate::compareTo).orElse(null);
        List<ShortTermStockIntradaySnapshot.Point> points = byMinute.values().stream()
                .filter(point -> point.minute().toLocalDate().equals(tradeDate)).toList();
        return new ShortTermStockIntradaySnapshot(symbol, tradeDate, source, "手", fetchedAt, points);
    }

    static ShortTermStockDailySnapshot dailySnapshot(
            String symbol, EastMoneyKLineSeries series, Instant fetchedAt
    ) {
        if (series == null || !series.complete() || series.rows().isEmpty()) {
            String detail = series == null || series.detail() == null ? "行情源未返回完整数据" : series.detail();
            throw new IllegalStateException("日线暂不可用：" + detail);
        }
        LocalDate today = fetchedAt.atZone(MARKET_ZONE).toLocalDate();
        for (EastMoneyKLine row : series.rows()) {
            if (row == null || !symbol.equals(row.symbol()) || row.tradeDate() == null
                    || row.tradeDate().isAfter(today)) {
                throw new IllegalStateException("日线证券或交易日期与请求不匹配");
            }
        }
        List<EastMoneyKLine> sorted = series.rows().stream()
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate)).toList();
        List<FirstBoardBar> bars = sorted.subList(Math.max(0, sorted.size() - 120), sorted.size()).stream()
                .map(row -> new FirstBoardBar(row.tradeDate(), row.open(), row.close(), row.high(), row.low(),
                        nonNegative(row.volume())))
                .toList();
        String source = switch (series.sourceName() == null ? "" : series.sourceName()) {
            case "TENCENT_QFQ_DAILY" -> "腾讯";
            case "EAST_MONEY_QFQ_DAILY" -> "东方财富";
            case "SINA_QFQ_DAILY" -> "新浪";
            default -> "日线行情源";
        };
        return new ShortTermStockDailySnapshot(symbol, "QFQ", "手", source, fetchedAt, bars);
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("股票代码必须为六位数字");
        }
    }

    private static boolean tradingMinute(LocalTime time) {
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 ? value : null;
    }
}
