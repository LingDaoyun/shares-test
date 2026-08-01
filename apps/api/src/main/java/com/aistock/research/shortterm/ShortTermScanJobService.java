package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.schedule.ShortTermFinalResultGate;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
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
    private static final Duration DEFAULT_SCAN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_JOB_COUNT = 80;
    private static final int WORKER_COUNT = 2;
    private static final int QUEUE_CAPACITY = 4;

    private final ShortTermService shortTermService;
    private final ResearchHistoryService researchHistoryService;
    private final TradingClockService tradingClockService;
    private final ShortTermFinalResultGate finalResultGate;
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
            ShortTermFinalResultGate finalResultGate
    ) {
        this(
                shortTermService, researchHistoryService, tradingClockService, finalResultGate,
                Clock.system(TradingClockService.CHINA_MARKET_ZONE),
                DEFAULT_SCAN_TIMEOUT);
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            Clock clock
    ) {
        this(shortTermService, researchHistoryService, tradingClockService, finalResultGate, clock, DEFAULT_SCAN_TIMEOUT);
    }

    ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            TradingClockService tradingClockService,
            ShortTermFinalResultGate finalResultGate,
            Clock clock,
            Duration scanTimeout
    ) {
        this.shortTermService = shortTermService;
        this.researchHistoryService = researchHistoryService;
        this.tradingClockService = tradingClockService;
        this.finalResultGate = finalResultGate;
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
        if (job.failIfRunning("短线右侧扫描超时，请降低扫描数量或稍后重试", clock.instant())) {
            future.cancel(true);
        }
    }

    private void runJob(MutableJob job, ShortTermScanRequest request) {
        try {
            ShortTermReport report = shortTermService.report(request);
            Instant finishedAt = clock.instant();
            ShortTermFinalResultGate.Result result =
                    finalResultGate.evaluateManual(report, finishedAt);
            recordHistorySafely(report);
            job.succeed(report, result, finishedAt);
        } catch (Exception exception) {
            logger.warn("短线右侧扫描任务失败，jobId={}", job.jobId(), exception);
            job.fail(rootMessage(exception), clock.instant());
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

    private static final class MutableJob {
        private final String jobId;
        private final LocalDate tradeDate;
        private final Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private String status;
        private String message;
        private ShortTermReport report;
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
            this.message = "短线右侧实时扫描中";
        }

        static MutableJob running(String jobId, LocalDate tradeDate, Instant createdAt) {
            return new MutableJob(jobId, tradeDate, createdAt);
        }

        String jobId() {
            return jobId;
        }

        synchronized void succeed(
                ShortTermReport report,
                ShortTermFinalResultGate.Result result,
                Instant finishedAt
        ) {
            if (!"RUNNING".equals(status)) {
                return;
            }
            this.status = "SUCCEEDED";
            this.resultStatus = result.status();
            this.blockedReasons = result.blockedReasons();
            this.finishedAt = finishedAt;
            this.message = result.message();
            this.report = report;
        }

        synchronized void fail(String message, Instant finishedAt) {
            if (!"RUNNING".equals(status)) {
                return;
            }
            failNow(message, finishedAt);
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
                    createdAt, startedAt, finishedAt, message, report);
        }
    }
}
