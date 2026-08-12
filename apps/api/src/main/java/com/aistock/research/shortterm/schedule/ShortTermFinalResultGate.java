package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class ShortTermFinalResultGate {

    private static final BigDecimal MINIMUM_COVERAGE = new BigDecimal("0.95");

    private final ShortTermAutomationSettings settings;
    private final TradingClockService tradingClock;

    public ShortTermFinalResultGate(
            ShortTermAutomationSettings settings,
            TradingClockService tradingClock
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tradingClock = Objects.requireNonNull(tradingClock, "tradingClock");
    }

    public Result evaluateManual(ShortTermReport report, Instant decisionCompletedAt) {
        LocalDate tradeDate = tradingClock.currentMarketDate();
        if (isClosedMarketCachePreview(tradeDate, report, decisionCompletedAt)) {
            String message = report.candidates() == null || report.candidates().isEmpty()
                    ? "缓存行情预览已完成，当前无合格候选；休市数据不作为今日买点"
                    : "缓存行情预览已完成，已生成策略候选；休市数据不作为今日买点";
            return new Result(
                    ShortTermSnapshotStatus.CACHE_PREVIEW,
                    message,
                    List.of("STATIC_CACHE_PREVIEW")
            );
        }
        Optional<Failure> failure = validate(
                tradeDate, report, decisionCompletedAt, decisionCompletedAt, false, true, false);
        if (failure.isPresent()) {
            Failure blocked = failure.orElseThrow();
            return blocked(blocked.reason(), blocked.message());
        }
        return classify(
                report,
                "手动分析已完成，当前无合格候选",
                "手动分析已完成，已生成当前时点候选"
        );
    }

    public Result evaluateScheduled(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt,
            Instant freshnessCheckedAt
    ) {
        return evaluate(tradeDate, report, decisionCompletedAt, freshnessCheckedAt, true);
    }

    private Result evaluate(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt,
            Instant freshnessCheckedAt,
            boolean enforceScheduledDeadline
    ) {
        Optional<Failure> failure = validate(
                tradeDate, report, decisionCompletedAt, freshnessCheckedAt, enforceScheduledDeadline, false, true);
        if (failure.isPresent()) {
            Failure blocked = failure.orElseThrow();
            return blocked(blocked.reason(), blocked.message());
        }
        return classify(
                report,
                "全部执行闸门通过，今日无合格候选",
                "14:49:40 前买入确认已就绪"
        );
    }

    private Result classify(ShortTermReport report, String noTradeMessage, String readyMessage) {
        ShortTermSnapshotStatus status = report.candidates() == null || report.candidates().isEmpty()
                ? ShortTermSnapshotStatus.NO_TRADE
                : ShortTermSnapshotStatus.FINAL_READY;
        String message = status == ShortTermSnapshotStatus.NO_TRADE
                ? noTradeMessage
                : readyMessage;
        return new Result(status, message, List.of());
    }

    private Optional<Failure> validate(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt,
            Instant freshnessCheckedAt,
            boolean enforceScheduledDeadline,
            boolean allowNonTradingFetchFreshness,
            boolean enforceFreshness
    ) {
        if (tradeDate == null || decisionCompletedAt == null
                || !marketDate(decisionCompletedAt).equals(tradeDate)
                || (enforceScheduledDeadline
                && marketTime(decisionCompletedAt).isAfter(settings.finalDeadline()))) {
            return Optional.of(new Failure(
                    "FINAL_DEADLINE_EXPIRED", "尾盘终选已超过完成截止时间"));
        }
        if (report == null) {
            return Optional.of(new Failure("FINAL_REPORT_MISSING", "尾盘终选报告缺失"));
        }
        ShortTermCoverageSnapshot coverage = report.coverage();
        if (coverage == null || !coverage.executionReliable()
                || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(MINIMUM_COVERAGE) < 0) {
            return Optional.of(new Failure("COVERAGE_BELOW_95", "全市场行情覆盖率低于95%"));
        }
        Instant cutoff = report.dataCutoffAt();
        if (cutoff == null) {
            return Optional.of(new Failure("CUTOFF_MISSING", "尾盘行情截止时间缺失"));
        }
        if (!marketDate(cutoff).equals(tradeDate)) {
            return Optional.of(new Failure("CUTOFF_WRONG_DATE", "尾盘行情不是当日数据"));
        }
        if (cutoff.isAfter(decisionCompletedAt)) {
            return Optional.of(new Failure("CUTOFF_AFTER_DECISION", "尾盘行情时间晚于决策时刻"));
        }
        Instant freshnessReference = cutoff;
        if (allowNonTradingFetchFreshness
                && report.tradingSession() != null
                && !report.tradingSession().regularAuctionOpen()) {
            freshnessReference = coverage.fetchedAt();
        }
        if (enforceFreshness
                && (freshnessCheckedAt == null
                || freshnessReference == null
                || freshnessReference.isAfter(freshnessCheckedAt)
                || Duration.between(freshnessReference, freshnessCheckedAt)
                .compareTo(settings.freshness()) > 0)) {
            return Optional.of(new Failure("QUOTE_STALE", "尾盘行情已经过期"));
        }
        return Optional.empty();
    }

    private boolean isClosedMarketCachePreview(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt
    ) {
        if (tradeDate == null || report == null || decisionCompletedAt == null
                || !marketDate(decisionCompletedAt).equals(tradeDate)
                || !tradingClock.isMarketClosedDay(tradeDate)) {
            return false;
        }
        ShortTermCoverageSnapshot coverage = report.coverage();
        if (coverage == null || !coverage.executionReliable()
                || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(MINIMUM_COVERAGE) < 0
                || coverage.fetchedAt() == null
                || coverage.fetchedAt().isAfter(decisionCompletedAt)) {
            return false;
        }
        if (Duration.between(coverage.fetchedAt(), decisionCompletedAt).compareTo(settings.freshness()) > 0) {
            return false;
        }
        Instant cutoff = report.dataCutoffAt();
        return cutoff != null && !cutoff.isAfter(decisionCompletedAt);
    }

    private LocalDate marketDate(Instant instant) {
        return LocalDateTime.ofInstant(instant, TradingClockService.CHINA_MARKET_ZONE).toLocalDate();
    }

    private LocalTime marketTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, TradingClockService.CHINA_MARKET_ZONE).toLocalTime();
    }

    private Result blocked(String reason, String message) {
        return new Result(ShortTermSnapshotStatus.DATA_BLOCKED, message, List.of(reason));
    }

    public record Result(
            ShortTermSnapshotStatus status,
            String message,
            List<String> blockedReasons
    ) {
    }

    private record Failure(String reason, String message) {
    }
}
