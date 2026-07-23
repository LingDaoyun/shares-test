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

    private static final BigDecimal MINIMUM_COVERAGE = new BigDecimal("0.90");

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
        if (decisionCompletedAt == null
                || tradingClock.isMarketClosedDay(tradeDate)
                || !marketDate(decisionCompletedAt).equals(tradeDate)
                || !insideTailWindow(marketTime(decisionCompletedAt))
                || report == null
                || report.tradingSession() == null
                || !report.tradingSession().closingDecisionWindow()) {
            return blocked(
                    "MANUAL_OUTSIDE_DECISION_WINDOW",
                    "手动扫描不在当日尾盘决策窗口，结果不可执行");
        }
        return evaluate(tradeDate, report, decisionCompletedAt, decisionCompletedAt, false);
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
                tradeDate, report, decisionCompletedAt, freshnessCheckedAt, enforceScheduledDeadline);
        if (failure.isPresent()) {
            Failure blocked = failure.orElseThrow();
            return blocked(blocked.reason(), blocked.message());
        }
        ShortTermSnapshotStatus status = report.candidates() == null || report.candidates().isEmpty()
                ? ShortTermSnapshotStatus.NO_TRADE
                : ShortTermSnapshotStatus.FINAL_READY;
        String message = status == ShortTermSnapshotStatus.NO_TRADE
                ? "全部执行闸门通过，今日无合格候选"
                : "尾盘最终结果已就绪";
        return new Result(status, message, List.of());
    }

    private Optional<Failure> validate(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt,
            Instant freshnessCheckedAt,
            boolean enforceScheduledDeadline
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
            return Optional.of(new Failure("COVERAGE_BELOW_90", "全市场行情覆盖率低于90%"));
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
        if (freshnessCheckedAt == null
                || Duration.between(cutoff, freshnessCheckedAt).compareTo(settings.freshness()) > 0) {
            return Optional.of(new Failure("QUOTE_STALE", "尾盘行情已经过期"));
        }
        return Optional.empty();
    }

    private boolean insideTailWindow(LocalTime time) {
        return !time.isBefore(TradingClockService.SHORT_TERM_ENTRY_START)
                && time.isBefore(TradingClockService.SHORT_TERM_ENTRY_EXCLUSIVE_END);
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
