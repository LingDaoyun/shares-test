package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.AsharePriceLimitRule;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FirstBoardAlertService {

    static final String RULE_VERSION = "first-board-alert-v1";
    private static final int FULL_MARKET_LIMIT = 6000;
    private static final BigDecimal MIN_CHANGE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CHANGE = new BigDecimal("7.00");
    private static final BigDecimal MIN_VOLUME_RATIO = new BigDecimal("0.80");
    private static final BigDecimal STRONG_VOLUME_RATIO = new BigDecimal("1.20");
    private static final BigDecimal ACCELERATING_VOLUME_RATIO = new BigDecimal("3.20");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final EastMoneyClient eastMoneyClient;
    private final FirstBoardBaselineService baselineService;
    private final TradingClockService tradingClockService;

    public FirstBoardAlertService(
            EastMoneyClient eastMoneyClient,
            FirstBoardBaselineService baselineService,
            TradingClockService tradingClockService
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.baselineService = baselineService;
        this.tradingClockService = tradingClockService;
    }

    public FirstBoardAlertSnapshot snapshot(boolean includeGrowth, boolean includeStar) {
        LocalDate tradeDate = effectiveTradeDate();
        FirstBoardBaselineAccess baselineAccess = baselineService.access(tradeDate);
        if (baselineAccess.status() != FirstBoardAlertStatus.READY || baselineAccess.snapshot() == null) {
            return progressSnapshot(baselineAccess, tradeDate);
        }
        try {
            return refresh(baselineAccess.snapshot(), includeGrowth, includeStar, Instant.now());
        } catch (RuntimeException exception) {
            return failedQuoteSnapshot(tradeDate, baselineAccess.snapshot());
        }
    }

    public Optional<FirstBoardBarSeries> bars(String symbol) {
        LocalDate tradeDate = effectiveTradeDate();
        FirstBoardBaselineAccess access = baselineService.access(tradeDate);
        if (access.status() != FirstBoardAlertStatus.READY || access.snapshot() == null) {
            return Optional.empty();
        }
        return baselineService.recentBars(tradeDate, symbol)
                .map(rows -> new FirstBoardBarSeries(symbol, tradeDate, access.snapshot().baselineDate(), rows));
    }

    private FirstBoardAlertSnapshot refresh(
            FirstBoardBaselineSnapshot baselineSnapshot,
            boolean includeGrowth,
            boolean includeStar,
            Instant generatedAt
    ) {
        AshareQuoteSnapshot quoteSnapshot = eastMoneyClient.fetchAshareQuoteSnapshot(FULL_MARKET_LIMIT);
        if (!quoteSnapshot.complete()) {
            throw new IllegalStateException("盘中全市场行情不完整");
        }
        Map<String, EastMoneyQuote> quotes = new LinkedHashMap<>();
        for (EastMoneyQuote quote : quoteSnapshot.quotes()) {
            if (quote != null && quote.symbol() != null) quotes.put(quote.symbol(), quote);
        }
        boolean hasCurrentTradeDate = quotes.values().stream()
                .anyMatch(quote -> baselineSnapshot.tradeDate().equals(quote.tradeDate()));
        if (!hasCurrentTradeDate) {
            throw new IllegalStateException("盘中行情交易日与当前交易日不一致");
        }

        List<FirstBoardBaseline> scoped = baselineSnapshot.stocks().values().stream()
                .filter(baseline -> boardAllowed(baseline.board(), includeGrowth, includeStar))
                .toList();
        List<FirstBoardAlertCandidate> latent = new ArrayList<>();
        List<FirstBoardAlertCandidate> previousLimitUp = new ArrayList<>();
        int missingQuoteCount = 0;
        int touchedLimitCount = 0;
        int lowVolumeRatioCount = 0;

        for (FirstBoardBaseline baseline : scoped) {
            EastMoneyQuote quote = quotes.get(baseline.symbol());
            if (!validQuote(quote, baselineSnapshot.tradeDate())) {
                missingQuoteCount++;
                continue;
            }
            if (quote.changePercent().compareTo(MIN_CHANGE) < 0
                    || quote.changePercent().compareTo(MAX_CHANGE) > 0) {
                continue;
            }
            if (AsharePriceLimitRule.hasTouchedLimitUp(
                    baseline.symbol(), baseline.previousClose(), quote.highPrice())) {
                touchedLimitCount++;
                continue;
            }
            if (quote.realtimeVolumeRatio().compareTo(MIN_VOLUME_RATIO) < 0) {
                lowVolumeRatioCount++;
                continue;
            }
            FirstBoardAlertLevel level = level(
                    baseline, quote.realtimeVolumeRatio(), baselineSnapshot.tradeDate());
            if (level == null) continue;
            BigDecimal drawdown = intradayDrawdown(quote.highPrice(), quote.latestPrice());
            FirstBoardAlertCandidate candidate = new FirstBoardAlertCandidate(
                    baseline.symbol(), baseline.name(), baseline.industry(), baseline.board(),
                    baseline.previousLimitUp() ? FirstBoardAlertLane.PREVIOUS_LIMIT_UP : FirstBoardAlertLane.LATENT,
                    level, quote.latestPrice(), quote.changePercent(), baseline.ma10(), baseline.ma20(),
                    baseline.ma20Slope(), baseline.rsi14(), baseline.macdHistogram(), baseline.technicalPassCount(),
                    quote.realtimeVolumeRatio(), baseline.previousDayVolumeRatio(),
                    baseline.recentFiveMaxVolumeRatio(), baseline.upperShadowRatio(), drawdown,
                    baseline.threeDayCloseRange()
            );
            if (baseline.previousLimitUp()) previousLimitUp.add(candidate);
            else latent.add(candidate);
        }

        latent.sort(latentComparator());
        previousLimitUp.sort(previousLimitUpComparator());
        FirstBoardAlertCoverage coverage = new FirstBoardAlertCoverage(
                baselineSnapshot.universeCount(), baselineSnapshot.supportedCount(),
                baselineSnapshot.stocks().size(), scoped.size(), quoteSnapshot.fetchedCount(),
                latent.size() + previousLimitUp.size(), baselineSnapshot.excludedNameCount(),
                baselineSnapshot.insufficientHistoryCount(), missingQuoteCount, touchedLimitCount,
                lowVolumeRatioCount
        );
        return new FirstBoardAlertSnapshot(
                FirstBoardAlertStatus.READY,
                baselineSnapshot.tradeDate(),
                baselineSnapshot.baselineDate(),
                generatedAt,
                quoteSnapshot.fetchedAt(),
                autoRefreshAllowed(),
                RULE_VERSION,
                "READY",
                coverage.eligibleCount(),
                coverage.scopedBaselineCount(),
                0,
                baselineSnapshot.generatedAt(),
                null,
                coverage,
                latent,
                previousLimitUp,
                List.of()
        );
    }

    private FirstBoardAlertLevel level(
            FirstBoardBaseline baseline,
            BigDecimal realtimeVolumeRatio,
            LocalDate scanTradeDate
    ) {
        if (baseline.technicalPassCount() < 4) return null;
        FirstBoardAlertLevel level;
        if (baseline.technicalPassCount() == 4 || realtimeVolumeRatio.compareTo(STRONG_VOLUME_RATIO) < 0) {
            level = FirstBoardAlertLevel.WATCH;
        } else if (realtimeVolumeRatio.compareTo(ACCELERATING_VOLUME_RATIO) > 0) {
            level = baseline.previousLimitUp()
                    ? FirstBoardAlertLevel.REPEAT_ACCELERATING : FirstBoardAlertLevel.ACCELERATING;
        } else {
            level = baseline.previousLimitUp()
                    ? FirstBoardAlertLevel.REPEAT_STRONG : FirstBoardAlertLevel.STRONG;
        }
        if (!baseline.previousLimitUp()) return level;
        LocalTime time = evaluationTime(scanTradeDate);
        if (!time.isBefore(LocalTime.of(11, 0))) return FirstBoardAlertLevel.WATCH;
        if (!time.isBefore(LocalTime.of(10, 30))) {
            if (level == FirstBoardAlertLevel.REPEAT_ACCELERATING) return FirstBoardAlertLevel.REPEAT_STRONG;
            if (level == FirstBoardAlertLevel.REPEAT_STRONG) return FirstBoardAlertLevel.WATCH;
        }
        return level;
    }

    private LocalDate effectiveTradeDate() {
        LocalDate currentDate = tradingClockService.currentMarketDate();
        return tradingClockService.isMarketClosedDay(currentDate)
                ? tradingClockService.previousTradingDay(currentDate)
                : currentDate;
    }

    private LocalTime evaluationTime(LocalDate scanTradeDate) {
        java.time.LocalDateTime now = tradingClockService.currentMarketDateTime();
        return scanTradeDate != null && scanTradeDate.equals(now.toLocalDate())
                ? now.toLocalTime()
                : TradingClockService.REGULAR_CLOSE;
    }

    private boolean validQuote(EastMoneyQuote quote, LocalDate tradeDate) {
        return quote != null && tradeDate.equals(quote.tradeDate())
                && quote.latestPrice() != null && quote.latestPrice().signum() > 0
                && quote.changePercent() != null && quote.highPrice() != null
                && quote.realtimeVolumeRatio() != null;
    }

    private boolean boardAllowed(FirstBoardBoard board, boolean includeGrowth, boolean includeStar) {
        return board == FirstBoardBoard.MAIN
                || (board == FirstBoardBoard.GROWTH && includeGrowth)
                || (board == FirstBoardBoard.STAR && includeStar);
    }

    private BigDecimal intradayDrawdown(BigDecimal high, BigDecimal latest) {
        if (high == null || latest == null || high.signum() <= 0) return null;
        BigDecimal drawdown = high.subtract(latest, MC)
                .max(BigDecimal.ZERO)
                .multiply(new BigDecimal("100"), MC)
                .divide(high, MC);
        return drawdown.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Comparator<FirstBoardAlertCandidate> latentComparator() {
        return Comparator.comparingInt((FirstBoardAlertCandidate candidate) -> levelRank(candidate.level())).reversed()
                .thenComparing(Comparator.comparingInt(FirstBoardAlertCandidate::technicalPassCount).reversed())
                .thenComparing(FirstBoardAlertCandidate::realtimeVolumeRatio, Comparator.reverseOrder())
                .thenComparing(candidate -> recentVolumePriority(candidate.recentFiveMaxVolumeRatio()))
                .thenComparing(FirstBoardAlertCandidate::ma20Slope, Comparator.reverseOrder())
                .thenComparing(FirstBoardAlertCandidate::macdHistogram, Comparator.reverseOrder())
                .thenComparing(FirstBoardAlertCandidate::symbol);
    }

    private Comparator<FirstBoardAlertCandidate> previousLimitUpComparator() {
        return Comparator.comparingInt((FirstBoardAlertCandidate candidate) -> levelRank(candidate.level())).reversed()
                .thenComparing(candidate -> previousVolumePriority(candidate.previousDayVolumeRatio()))
                .thenComparing(candidate -> drawdownPenalty(candidate) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(FirstBoardAlertCandidate::technicalPassCount).reversed())
                .thenComparing(FirstBoardAlertCandidate::realtimeVolumeRatio, Comparator.reverseOrder())
                .thenComparing(candidate -> recentVolumePriority(candidate.recentFiveMaxVolumeRatio()))
                .thenComparing(FirstBoardAlertCandidate::ma20Slope, Comparator.reverseOrder())
                .thenComparing(FirstBoardAlertCandidate::macdHistogram, Comparator.reverseOrder())
                .thenComparing(FirstBoardAlertCandidate::symbol);
    }

    private int levelRank(FirstBoardAlertLevel level) {
        return switch (level) {
            case ACCELERATING, REPEAT_ACCELERATING -> 3;
            case STRONG, REPEAT_STRONG -> 2;
            case WATCH -> 1;
        };
    }

    private int previousVolumePriority(BigDecimal ratio) {
        if (ratio != null && ratio.compareTo(new BigDecimal("0.80")) < 0) return 0;
        if (ratio != null && ratio.compareTo(new BigDecimal("3.00")) >= 0) return 2;
        return 1;
    }

    private int recentVolumePriority(BigDecimal ratio) {
        return ratio != null && ratio.compareTo(new BigDecimal("1.50")) >= 0 ? 0 : 1;
    }

    private boolean drawdownPenalty(FirstBoardAlertCandidate candidate) {
        return candidate.realtimeVolumeRatio() != null
                && candidate.realtimeVolumeRatio().compareTo(STRONG_VOLUME_RATIO) >= 0
                && candidate.intradayDrawdown() != null
                && candidate.intradayDrawdown().compareTo(new BigDecimal("3.00")) >= 0;
    }

    private FirstBoardAlertSnapshot progressSnapshot(
            FirstBoardBaselineAccess access, LocalDate tradeDate
    ) {
        FirstBoardIngestionProgress progress = access.progress();
        return new FirstBoardAlertSnapshot(
                access.status(), tradeDate, progress == null ? null : progress.tradeDate(), Instant.now(), null,
                autoRefreshAllowed(), RULE_VERSION,
                progress == null ? access.status().name() : progress.phase(),
                progress == null ? 0 : progress.completed(),
                progress == null ? 0 : progress.total(),
                progress == null ? 0 : progress.failed(),
                null,
                progress == null ? null : progress.nextRetryAt(),
                new FirstBoardAlertCoverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), access.dataGaps()
        );
    }

    private FirstBoardAlertSnapshot failedQuoteSnapshot(
            LocalDate tradeDate, FirstBoardBaselineSnapshot baseline
    ) {
        return new FirstBoardAlertSnapshot(
                FirstBoardAlertStatus.FAILED, tradeDate, baseline.baselineDate(), Instant.now(), null,
                autoRefreshAllowed(), RULE_VERSION, "QUOTE_FAILED", 0, 0, 1,
                baseline.generatedAt(), Instant.now().plusSeconds(60),
                new FirstBoardAlertCoverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of("盘中全市场行情不可用")
        );
    }

    private boolean autoRefreshAllowed() {
        java.time.LocalDateTime now = tradingClockService.currentMarketDateTime();
        LocalTime time = now.toLocalTime();
        return !tradingClockService.isMarketClosedDay(now.toLocalDate())
                && !time.isBefore(TradingClockService.OPEN_CALL_START)
                && !time.isAfter(TradingClockService.REGULAR_CLOSE);
    }

}
