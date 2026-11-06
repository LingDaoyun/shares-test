package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ShortTermDailyScanService {
    private static final Logger log = LoggerFactory.getLogger(ShortTermDailyScanService.class);
    private final ShortTermDailySnapshotStore store;
    private final ShortTermService scanner;
    private final TradingClockService clock;
    private final ShortTermScanResultCache resultCache;
    private final ConcurrentMap<LocalDate, Instant> activeRuns = new ConcurrentHashMap<>();
    public ShortTermDailyScanService(ShortTermDailySnapshotStore store, ShortTermService scanner,
                                    TradingClockService clock,
                                    ShortTermScanResultCache resultCache) {
        this.store = store; this.scanner = scanner; this.clock = clock;
        this.resultCache = resultCache;
    }

    @Scheduled(cron = "${research.short-term.daily-scan.cron:0 0 */2 * * *}", zone = "Asia/Shanghai")
    public synchronized void scanAtTwo() {
        if (!activeRuns.isEmpty()) return;
        Instant started = now();
        LocalDate date = started.atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate();
        ShortTermDailySnapshot running = new ShortTermDailySnapshot(date, scheduled(started), started, null,
                "RUNNING", "每两小时定时扫描中；结果形成后可直接加载。", null, null, List.of());
        if (!store.claim(running)) return;
        dispatchScan(running);
    }

    public synchronized ShortTermDailySnapshot retryToday() {
        LocalDate date = clock.currentMarketDate();
        LocalTime time = clock.currentMarketDateTime().toLocalTime();
        if (clock.isMarketClosedDay(date) || clock.verifiedTradingDayAfter(date, 0).isEmpty()
                || time.isBefore(LocalTime.of(14, 0)) || !time.isBefore(LocalTime.of(15, 0))) {
            throw new IllegalArgumentException("仅支持已核实交易日14:00至15:00重试今日扫描。");
        }
        if (!activeRuns.isEmpty()) return latest();
        Instant started = now();
        ShortTermDailySnapshot running = new ShortTermDailySnapshot(date, scheduled(started), started, null,
                "RUNNING", "今日短线扫描重试中；保留本次实际采样时间。", null, null, List.of());
        if (!store.claimRetry(running)) return latest();
        dispatchScan(running);
        return running;
    }

    private void dispatchScan(ShortTermDailySnapshot running) {
        // Count queued work as active and leave the shared scheduler free for other market tasks.
        activeRuns.put(running.tradeDate(), running.startedAt());
        CompletableFuture.runAsync(() -> {
            try { scan(running); }
            finally { activeRuns.remove(running.tradeDate(), running.startedAt()); }
        });
    }

    private void scan(ShortTermDailySnapshot running) {
        LocalDate date = running.tradeDate();
        List<String> gaps = new ArrayList<>();
        ShortTermReport report = null;
        try {
            report = scanner.report(new ShortTermScanRequest(0, 0, 0, null, null, null, null, null, null, false, false));
            if (report.coverage() == null || !report.coverage().executionReliable()) {
                gaps.add("右侧扫描行情覆盖或点时语义未通过，未发布该报告。");
                report = null;
            } else {
                if (report.coverage().missingCount() > 0) {
                    gaps.add("右侧行情仍有" + report.coverage().missingCount() + "只缺口，未作为完整覆盖。");
                }
                boolean stoppedByMarketRisk = report.marketRegime() != null && report.marketRegime().riskOff()
                        || report.marketSentiment() != null && "极端退潮".equals(report.marketSentiment().phase());
                var technical = report.technicalReviewCoverage();
                if (!stoppedByMarketRisk && technical != null && technical.missingCount() > 0) {
                    gaps.add("右侧K线复核：预选" + technical.quotePreselectedCount() + "只，已请求"
                            + technical.requestedCount() + "只，有效" + technical.sufficientCount()
                            + "只；缺口" + technical.missingCount() + "只，保留已完成候选。");
                }
            }
        } catch (RuntimeException error) {
            log.warn("定时右侧候选扫描失败", error);
            gaps.add("右侧候选未完成：" + error.getMessage());
        }
        String status = report == null ? "FAILED" : gaps.isEmpty() ? "READY" : "PARTIAL";
        ShortTermDailySnapshot completed = new ShortTermDailySnapshot(date, running.scheduledAt(), running.startedAt(), now(), status,
                "READY".equals(status) ? "本轮短线扫描已保存，可直接查看。"
                        : "PARTIAL".equals(status) ? "扫描部分完成，请结合数据缺口查看。" : "定时扫描失败，未将缺失数据当作零候选。",
                report, null, List.copyOf(gaps), running.runId());
        store.finishAndPublish(completed, resultCache);
    }

    public ShortTermDailySnapshot resultCache() {
        return resultCache.latest().orElseGet(() -> new ShortTermDailySnapshot(clock.currentMarketDate(), null,
                null, null, "NOT_STARTED", "尚无已完成的扫描结果；完成一次扫描后可加载。", null, null, List.of()));
    }

    public ShortTermDailySnapshot latest() {
        var saved = store.latest();
        Instant current = now();
        if (saved.isEmpty()) return new ShortTermDailySnapshot(current.atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate(), scheduled(current),
                null, null, "NOT_STARTED", "尚无已保存的定时扫描结果；系统按北京时间每两小时自动扫描。", null, null, List.of());
        var value = saved.get();
        if ("RUNNING".equals(value.status()) && value.startedAt() != null
                && !value.startedAt().equals(activeRuns.get(value.tradeDate()))
                && Duration.between(value.startedAt(), current).toMinutes() >= 30) {
            return new ShortTermDailySnapshot(value.tradeDate(), value.scheduledAt(), value.startedAt(), null,
                    "INTERRUPTED", "任务运行超过30分钟或服务曾中断，尚无可确认完成的结果。", null, null,
                    List.of("不会把未完成任务当作扫描成功；可使用手动扫描查看当前行情。"));
        }
        return value;
    }
    private Instant now() { return clock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant(); }
    private Instant scheduled(Instant current) {
        var local = current.atZone(TradingClockService.CHINA_MARKET_ZONE);
        return local.withHour(local.getHour() / 2 * 2).withMinute(0).withSecond(0).withNano(0).toInstant();
    }
}
