package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.schedule.ShortTermFinalResultGate;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.shortterm.schedule.ShortTermScanResultCache;
import com.aistock.research.shortterm.schedule.ShortTermDailySnapshot;
import com.aistock.research.shortterm.validation.ShortTermObservationService;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ShortTermScanJobService {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermScanJobService.class);
    private static final Duration FINISHED_JOB_RETENTION = Duration.ofMinutes(30);
    private static final Duration DEFAULT_SCAN_TIMEOUT = Duration.ofMinutes(45);
    private static final int MAX_JOB_COUNT = 80;
    private static final int WORKER_COUNT = 2;
    private static final int QUEUE_CAPACITY = 4;

    private final ShortTermService shortTermService;
    private final ResearchHistoryService researchHistoryService;
    private final TradingClockService tradingClockService;
    private final ShortTermFinalResultGate finalResultGate;
    private final ShortTermObservationService observationService;
    private final ShortTermScanResultCache resultCache;
    private final Clock clock;
    private final Duration scanTimeout;
    private final ConcurrentMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final ExecutorService executorService;
    private final ScheduledExecutorService timeoutExecutorService;

    @Autowired
    public ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            ShortTermObservationService observationService,
            ShortTermScanResultCache resultCache,
            @Value("${research.short-term.scan-job-timeout:PT45M}") String scanTimeout
    ) {
        this(
                shortTermService, researchHistoryService, tradingClockService, finalResultGate,
                observationService, resultCache,
                Clock.system(TradingClockService.CHINA_MARKET_ZONE),
                parseScanTimeout(scanTimeout));
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            Clock clock
    ) {
        this(shortTermService, researchHistoryService, tradingClockService, finalResultGate,
                null, clock, DEFAULT_SCAN_TIMEOUT);
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            Clock clock,
            Duration scanTimeout
    ) {
        this(shortTermService, researchHistoryService, tradingClockService, finalResultGate,
                null, clock, scanTimeout);
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            ShortTermObservationService observationService,
            Clock clock,
            Duration scanTimeout
    ) {
        this(shortTermService, researchHistoryService, tradingClockService, finalResultGate, observationService,
                null, clock, scanTimeout);
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            ShortTermObservationService observationService,
            ShortTermScanResultCache resultCache,
            Clock clock,
            Duration scanTimeout
    ) {
        this.shortTermService = shortTermService;
        this.researchHistoryService = researchHistoryService;
        this.tradingClockService = tradingClockService;
        this.finalResultGate = finalResultGate;
        this.observationService = observationService;
        this.resultCache = resultCache;
        this.clock = clock.withZone(TradingClockService.CHINA_MARKET_ZONE);
        this.scanTimeout = scanTimeout == null || scanTimeout.isNegative() || scanTimeout.isZero()
                ? DEFAULT_SCAN_TIMEOUT
                : scanTimeout;
        this.executorService = new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                scanThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.timeoutExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("short-term-scan-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ShortTermScanJobStatus start(ShortTermScanRequest request) {
        cleanupFinishedJobs();
        ShortTermScanRequest safeRequest = request == null ? ShortTermScanRequest.empty() : request;
        String jobId = UUID.randomUUID().toString();
        MutableJob job = MutableJob.running(jobId, tradingClockService.currentMarketDate(), clock.instant());
        jobs.put(jobId, job);
        try {
            Future<?> future = executorService.submit(() -> runJob(job, safeRequest));
            timeoutExecutorService.schedule(
                    () -> timeoutJob(job, future),
                    scanTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException exception) {
            jobs.remove(jobId);
            throw new IllegalStateException("短线扫描任务繁忙，请等待当前任务完成后再试", exception);
        }
        return job.snapshot();
    }

    public ShortTermScanJobStatus get(String jobId) {
        MutableJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("短线扫描任务不存在或已过期：" + jobId);
        }
        return job.snapshot();
    }

    @PreDestroy
    public void shutdown() {
        timeoutExecutorService.shutdownNow();
        executorService.shutdownNow();
    }

    private void timeoutJob(MutableJob job, Future<?> future) {
        if (job.failIfRunning("短线扫描超时，本次结果未更新缓存，请稍后重试", clock.instant())) {
            future.cancel(true);
        }
    }

    private void runJob(MutableJob job, ShortTermScanRequest request) {
        List<String> gaps = new ArrayList<>();
        ShortTermReport report = null;
        ShortTermFinalResultGate.Result result = null;
        try {
            if (!"RUNNING".equals(job.snapshot().status())) return;
            try {
                report = shortTermService.report(request);
                if (report == null) throw new IllegalStateException("右侧扫描未返回报告");
                result = finalResultGate.evaluateManual(report, clock.instant());
                if (result == null) throw new IllegalStateException("右侧扫描未返回结果校验状态");
                if (!visibleRightResult(result.status()) || result.status() == ShortTermSnapshotStatus.CACHE_PREVIEW) {
                    gaps.add(result.message());
                    gaps.addAll(result.blockedReasons());
                }
                appendRightDataGaps(report, gaps);
            } catch (Exception exception) {
                logger.warn("短线右侧扫描任务失败，jobId={}", job.jobId(), exception);
                gaps.add("右侧候选未完成：" + rootMessage(exception));
            }
            Instant finishedAt = clock.instant();
            if (result == null) {
                job.fail(String.join("；", gaps), finishedAt, gaps);
                return;
            }
            ShortTermReport cacheReport = visibleRightResult(result.status()) ? report : null;
            ShortTermDailySnapshot snapshot = new ShortTermDailySnapshot(job.tradeDate, null, job.startedAt, finishedAt,
                        cacheReport != null && gaps.isEmpty() ? "READY" : "PARTIAL",
                        !visibleRightResult(result.status()) || result.status() == ShortTermSnapshotStatus.CACHE_PREVIEW
                                ? result.message() + " 本次扫描结果已保存。"
                                : "手动扫描结果已保存。",
                        cacheReport, null, gaps, job.jobId());
            if (!"RUNNING".equals(job.snapshot().status())) return;
            recordHistorySafely(report);
            recordObservationSafely(job.jobId(), report, result, finishedAt);
            // Publication and cache replacement share the timeout guard; an interrupted provider may return late.
            synchronized (job) {
                if (!"RUNNING".equals(job.status)) return;
                String message = result.message();
                if (resultCache != null) {
                    boolean saved = resultCache.save(snapshot);
                    message += saved ? " 扫描结果已缓存。" : " 本次结果未替换已保存缓存。";
                }
                job.succeed(report, gaps, result, message, finishedAt);
            }
        } catch (Exception exception) {
            logger.warn("短线扫描结果发布失败，jobId={}", job.jobId(), exception);
            job.fail(rootMessage(exception), clock.instant(), gaps);
        }
    }

    private boolean visibleRightResult(ShortTermSnapshotStatus status) {
        return status == ShortTermSnapshotStatus.FINAL_READY || status == ShortTermSnapshotStatus.NO_TRADE
                || status == ShortTermSnapshotStatus.CACHE_PREVIEW;
    }

    private void appendRightDataGaps(ShortTermReport report, List<String> gaps) {
        if (report.coverage() != null && report.coverage().missingCount() > 0) {
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

    private void recordHistorySafely(ShortTermReport report) {
        if (researchHistoryService == null) {
            return;
        }
        try {
            researchHistoryService.recordShortTermReport(report);
        } catch (RuntimeException exception) {
            logger.warn("短线扫描历史归档失败，不影响本次实时报告，原因：{}", exception.getMessage());
        }
    }

    private void recordObservationSafely(
            String jobId,
            ShortTermReport report,
            ShortTermFinalResultGate.Result result,
            Instant publishedAt
    ) {
        if (observationService == null || result == null) {
            return;
        }
        try {
            observationService.captureManual(
                    jobId, report, result.status(), result.blockedReasons(), publishedAt);
        } catch (RuntimeException exception) {
            logger.warn("短线手动扫描观察样本归档失败，不影响本次实时报告，原因：{}", exception.getMessage());
        }
    }

    private void cleanupFinishedJobs() {
        Instant expiredBefore = clock.instant().minus(FINISHED_JOB_RETENTION);
        jobs.entrySet().removeIf(entry -> {
            MutableJob job = entry.getValue();
            ShortTermScanJobStatus snapshot = job.snapshot();
            return snapshot.finishedAt() != null && snapshot.finishedAt().isBefore(expiredBefore);
        });
        if (jobs.size() <= MAX_JOB_COUNT) {
            return;
        }
        jobs.values().stream()
                .filter(job -> job.snapshot().finishedAt() != null)
                .sorted(Comparator.comparing(job -> job.snapshot().finishedAt()))
                .limit(Math.max(0, jobs.size() - MAX_JOB_COUNT))
                .forEach(job -> jobs.remove(job.jobId()));
    }

    private ThreadFactory scanThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("short-term-scan-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String rootMessage(Exception exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null || cursor.getMessage().isBlank()
                ? exception.getMessage()
                : cursor.getMessage();
    }

    private static Duration parseScanTimeout(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SCAN_TIMEOUT;
        }
        try {
            return Duration.parse(value.trim());
        } catch (RuntimeException exception) {
            logger.warn("Invalid refreshed setting research.short-term.scan-job-timeout={}, using default {}",
                    value, DEFAULT_SCAN_TIMEOUT);
            return DEFAULT_SCAN_TIMEOUT;
        }
    }

    private static final class MutableJob {
        private final String jobId;
        private final LocalDate tradeDate;
        private final Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private String status;
        private String message;
        private ShortTermReport report;
        private List<String> dataGaps = List.of();
        private ShortTermSnapshotStatus resultStatus;
        private List<String> blockedReasons;

        private MutableJob(String jobId, LocalDate tradeDate, Instant createdAt) {
            this.jobId = jobId;
            this.tradeDate = tradeDate;
            this.createdAt = createdAt;
            this.startedAt = this.createdAt;
            this.status = "RUNNING";
            this.resultStatus = ShortTermSnapshotStatus.RUNNING;
            this.blockedReasons = List.of();
            this.message = "短线右侧扫描中";
        }

        static MutableJob running(String jobId, LocalDate tradeDate, Instant createdAt) {
            return new MutableJob(jobId, tradeDate, createdAt);
        }

        String jobId() {
            return jobId;
        }

        synchronized void succeed(
                ShortTermReport report,
                List<String> dataGaps,
                ShortTermFinalResultGate.Result result,
                String message,
                Instant finishedAt
        ) {
            if (!"RUNNING".equals(status)) {
                return;
            }
            this.status = "SUCCEEDED";
            this.resultStatus = result.status();
            this.blockedReasons = result.blockedReasons();
            this.finishedAt = finishedAt;
            this.message = message;
            this.report = report;
            this.dataGaps = List.copyOf(dataGaps);
        }

        synchronized void fail(String message, Instant finishedAt, List<String> dataGaps) {
            if (!"RUNNING".equals(status)) return;
            failNow(message, finishedAt);
            this.dataGaps = List.copyOf(dataGaps);
        }

        synchronized boolean failIfRunning(String message, Instant finishedAt) {
            if (!"RUNNING".equals(status)) {
                return false;
            }
            failNow(message, finishedAt);
            return true;
        }

        private void failNow(String message, Instant finishedAt) {
            this.status = "FAILED";
            this.resultStatus = ShortTermSnapshotStatus.FAILED;
            this.blockedReasons = List.of();
            this.finishedAt = finishedAt;
            this.message = message == null || message.isBlank() ? "短线右侧扫描失败" : message;
            this.report = null;
        }

        synchronized ShortTermScanJobStatus snapshot() {
            return new ShortTermScanJobStatus(
                    jobId, status, tradeDate, resultStatus, blockedReasons,
                    createdAt, startedAt, finishedAt, message, report, null, dataGaps);
        }
    }
}
