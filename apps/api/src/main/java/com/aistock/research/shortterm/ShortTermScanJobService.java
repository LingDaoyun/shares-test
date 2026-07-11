package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ShortTermScanJobService {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermScanJobService.class);
    private static final Duration FINISHED_JOB_RETENTION = Duration.ofMinutes(30);
    private static final int MAX_JOB_COUNT = 80;
    private static final int WORKER_COUNT = 2;
    private static final int QUEUE_CAPACITY = 4;

    private final ShortTermService shortTermService;
    private final ResearchHistoryService researchHistoryService;
    private final ConcurrentMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final ExecutorService executorService;

    public ShortTermScanJobService(ShortTermService shortTermService) {
        this(shortTermService, null);
    }

    @Autowired
    public ShortTermScanJobService(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService
    ) {
        this.shortTermService = shortTermService;
        this.researchHistoryService = researchHistoryService;
        this.executorService = new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                scanThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public ShortTermScanJobStatus start(ShortTermScanRequest request) {
        cleanupFinishedJobs();
        ShortTermScanRequest safeRequest = request == null ? ShortTermScanRequest.empty() : request;
        String jobId = UUID.randomUUID().toString();
        MutableJob job = MutableJob.running(jobId);
        jobs.put(jobId, job);
        try {
            executorService.submit(() -> runJob(job, safeRequest));
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
        executorService.shutdownNow();
    }

    private void runJob(MutableJob job, ShortTermScanRequest request) {
        try {
            ShortTermReport report = shortTermService.report(
                    request.limit(),
                    request.scanLimit(),
                    request.klineLimit(),
                    request.minAmount(),
                    request.maxPe(),
                    request.maxPb(),
                    request.minVolumeRatio(),
                    request.maxEntryRise(),
                    request.maxDistanceToMa20(),
                    request.minFinancialScore()
            );
            recordHistorySafely(report);
            job.succeed(report);
        } catch (Exception exception) {
            logger.warn("短线右侧扫描任务失败，jobId={}", job.jobId(), exception);
            job.fail(rootMessage(exception));
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
        Instant expiredBefore = Instant.now().minus(FINISHED_JOB_RETENTION);
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
        private final Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private String status;
        private String message;
        private ShortTermReport report;

        private MutableJob(String jobId) {
            this.jobId = jobId;
            this.createdAt = Instant.now();
            this.startedAt = this.createdAt;
            this.status = "RUNNING";
            this.message = "短线右侧实时扫描中";
        }

        static MutableJob running(String jobId) {
            return new MutableJob(jobId);
        }

        String jobId() {
            return jobId;
        }

        synchronized void succeed(ShortTermReport report) {
            this.status = "SUCCEEDED";
            this.finishedAt = Instant.now();
            this.message = "短线右侧扫描完成";
            this.report = report;
        }

        synchronized void fail(String message) {
            this.status = "FAILED";
            this.finishedAt = Instant.now();
            this.message = message == null || message.isBlank() ? "短线右侧扫描失败" : message;
            this.report = null;
        }

        synchronized ShortTermScanJobStatus snapshot() {
            return new ShortTermScanJobStatus(jobId, status, createdAt, startedAt, finishedAt, message, report);
        }
    }
}
