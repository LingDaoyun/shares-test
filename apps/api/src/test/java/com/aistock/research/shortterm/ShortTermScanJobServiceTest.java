package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.schedule.ShortTermFinalResultGate;
import com.aistock.research.shortterm.schedule.ShortTermDailySnapshot;
import com.aistock.research.shortterm.schedule.ShortTermScanResultCache;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.shortterm.validation.ShortTermObservationService;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.TradingSessionSnapshot;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;

class ShortTermScanJobServiceTest {

    @Test
    void shouldUseConfiguredScanTimeoutFromSpringProperties() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("research.short-term.scan-job-timeout=PT15M").applyTo(context);
        context.registerBean(ShortTermService.class, () -> mock(ShortTermService.class));
        context.registerBean(ResearchHistoryService.class, () -> mock(ResearchHistoryService.class));
        context.registerBean(TradingClockService.class, () -> mock(TradingClockService.class));
        context.registerBean(ShortTermFinalResultGate.class, () -> mock(ShortTermFinalResultGate.class));
        context.registerBean(ShortTermObservationService.class, () -> mock(ShortTermObservationService.class));
        context.registerBean(ShortTermScanResultCache.class, () -> mock(ShortTermScanResultCache.class));
        context.register(ShortTermScanJobService.class);

        try {
            context.refresh();

            assertThat(configuredTimeout(context.getBean(ShortTermScanJobService.class)))
                    .isEqualTo(Duration.ofMinutes(15));
        } finally {
            context.close();
        }
    }

    @Test
    void shouldRunScanJobAndExposeSucceededReport() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        ResearchHistoryService researchHistoryService = mock(ResearchHistoryService.class);
        ShortTermReport report = sampleReport();
        ShortTermScanJobService service = service(shortTermService, researchHistoryService);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            ShortTermScanRequest request = new ShortTermScanRequest(
                    3,
                    120,
                    20,
                    new BigDecimal("100000000"),
                    new BigDecimal("1.20"),
                    new BigDecimal("3.50"),
                    new BigDecimal("7.00"),
                    new BigDecimal("60"),
                    true
            );
            when(shortTermService.report(eq(request))).thenAnswer(invocation -> {
                running.countDown();
                release.await(10, TimeUnit.SECONDS);
                return report;
            });
            ShortTermScanJobStatus started = service.start(request);

            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(started.status()).isEqualTo("RUNNING");
            assertThat(started.message()).isEqualTo("短线右侧扫描中");
            assertThat(started.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING);
            release.countDown();
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.DATA_BLOCKED);
            assertThat(finished.strategyVersion()).isEqualTo("short-term-research-v8-intraday-overnight");
            assertThat(finished.report()).isSameAs(report);
            assertThat(finished.message()).contains("不可执行");
            verify(shortTermService).report(eq(request));
            verify(researchHistoryService).recordShortTermReport(report);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void shouldExposeFailedStatusWhenRealtimeScanFails() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenThrow(new IllegalStateException("短线右侧实时行情加载失败"));
        ShortTermScanJobService service = service(shortTermService, null);

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FAILED);
            assertThat(finished.report()).isNull();
            assertThat(finished.message()).contains("实时行情加载失败");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldArchiveManualObservationWithItsActualResultGateStatus() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        ResearchHistoryService historyService = mock(ResearchHistoryService.class);
        ShortTermObservationService observationService = mock(ShortTermObservationService.class);
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        ShortTermReport report = sampleReport();
        Instant now = Instant.parse("2026-07-23T06:49:00Z");
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        when(shortTermService.report(eq(ShortTermScanRequest.empty()))).thenReturn(report);
        when(gate.evaluateManual(eq(report), any())).thenReturn(new ShortTermFinalResultGate.Result(
                ShortTermSnapshotStatus.FINAL_READY, "手动终选已就绪", List.of()));
        ShortTermScanJobService service = new ShortTermScanJobService(
                shortTermService, historyService, tradingClock, gate, observationService,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            awaitFinished(service, started.jobId());

            verify(observationService).captureManual(
                    eq(started.jobId()), eq(report), eq(ShortTermSnapshotStatus.FINAL_READY),
                    eq(List.of()), eq(now));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldRejectNewScanWhenWorkersAndBoundedQueueAreFull() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenAnswer(invocation -> {
                    running.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return sampleReport();
                });
        ShortTermScanJobService service = service(shortTermService, null);

        try {
            for (int index = 0; index < 6; index++) {
                service.start(ShortTermScanRequest.empty());
            }
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.start(ShortTermScanRequest.empty()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("任务繁忙");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void shouldFailRunningScanWhenItExceedsTimeout() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        CountDownLatch release = new CountDownLatch(1);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return sampleReport();
                });
        ShortTermScanJobService service = service(shortTermService, null, Duration.ofMillis(50));

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.FAILED);
            assertThat(finished.message()).contains("超时");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void cachesRightSideResultWithoutRunningTheRemovedShapeScan() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ShortTermReport report = sampleReport();
        when(scanner.report(any())).thenReturn(report);
        when(cache.save(any())).thenReturn(true);
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.report()).isSameAs(report);
            assertThat(finished.upperShadow()).isNull();
            assertThat(finished.dataGaps()).isEmpty();
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().runId()).isEqualTo(finished.jobId());
            assertThat(saved.getValue().scheduledAt()).isNull();
            assertThat(saved.getValue().startedAt()).isEqualTo(finished.startedAt());
            assertThat(saved.getValue().report()).isSameAs(report);
            assertThat(saved.getValue().upperShadow()).isNull();
            assertThat(saved.getValue().status()).isEqualTo("READY");
            assertThat(finished.message()).doesNotContain("横盘", "长上影", "联合", "两类");
            assertThat(saved.getValue().message()).isEqualTo("手动扫描结果已保存。");
            var order = inOrder(scanner, cache);
            order.verify(scanner).report(ShortTermScanRequest.empty());
            order.verify(cache).save(any());
        } finally { service.shutdown(); }
    }

    @Test
    void doesNotReplaceCacheWhenRightSideScanFails() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        when(scanner.report(any())).thenThrow(new IllegalStateException("右侧来源失败"));
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.FAILED);
            assertThat(finished.report()).isNull();
            assertThat(finished.upperShadow()).isNull();
            assertThat(finished.dataGaps()).anyMatch(gap -> gap.contains("右侧来源失败"));
            verify(cache, never()).save(any());
        } finally { service.shutdown(); }
    }

    @Test
    void keepsBlockedRightReportOnlyInJobDiagnostics() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ShortTermReport report = sampleReport();
        when(scanner.report(any())).thenReturn(report);
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.DATA_BLOCKED,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.report()).isSameAs(report);
            assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().report()).isNull();
            assertThat(saved.getValue().status()).isEqualTo("PARTIAL");
            assertThat(saved.getValue().dataGaps()).contains("右侧DATA_BLOCKED", "覆盖不足");
        } finally { service.shutdown(); }
    }

    @Test
    void savesLatestDataBlockedOutcomeWithoutAnIneligibleReport() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ShortTermReport report = sampleReport();
        when(scanner.report(any())).thenReturn(report);
        when(cache.save(any())).thenReturn(true);
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.DATA_BLOCKED,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
            assertThat(finished.report()).isSameAs(report);
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().runId()).isEqualTo(finished.jobId());
            assertThat(saved.getValue().startedAt()).isEqualTo(finished.startedAt());
            assertThat(saved.getValue().finishedAt()).isEqualTo(finished.finishedAt());
            assertThat(saved.getValue().status()).isEqualTo("PARTIAL");
            assertThat(saved.getValue().report()).isNull();
            assertThat(saved.getValue().upperShadow()).isNull();
            assertThat(saved.getValue().message()).contains("右侧DATA_BLOCKED");
            assertThat(saved.getValue().dataGaps()).containsExactly("右侧DATA_BLOCKED", "覆盖不足");
            verify(cache, never()).latest();
        } finally { service.shutdown(); }
    }

    @Test
    void cachesACompleteZeroCandidateRightReport() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ShortTermReport report = sampleReport();
        when(scanner.report(any())).thenReturn(report);
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.upperShadow()).isNull();
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().report()).isSameAs(report);
            assertThat(saved.getValue().upperShadow()).isNull();
            assertThat(saved.getValue().status()).isEqualTo("READY");
            assertThat(saved.getValue().dataGaps()).isEmpty();
            verify(cache, never()).latest();
        } finally { service.shutdown(); }
    }

    @Test
    void doesNotPublishLateResultsAfterTimeoutEvenWhenProviderIgnoresInterruption() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        when(scanner.report(any())).thenAnswer(invocation -> {
            running.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate an uninterruptible provider. */ }
            }
            returned.countDown();
            return sampleReport();
        });
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMillis(80));
        try {
            String jobId = service.start(null).jobId();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitFinished(service, jobId).status()).isEqualTo("FAILED");
            release.countDown();
            assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
            service.shutdown();
            Field workerField = ShortTermScanJobService.class.getDeclaredField("executorService");
            workerField.setAccessible(true);
            assertThat(((java.util.concurrent.ExecutorService) workerField.get(service))
                    .awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(jobId).status()).isEqualTo("FAILED");
            verify(cache, never()).save(any());
        } finally { release.countDown(); service.shutdown(); }
    }

    @Test
    void failsTheJobWhenResultCannotBeSaved() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        when(scanner.report(any())).thenReturn(sampleReport());
        when(cache.save(any())).thenThrow(new IllegalStateException("缓存写入失败"));
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.message()).contains("缓存写入失败");
        } finally { service.shutdown(); }
    }

    @Test
    void marksActualRightSideQuoteAndTechnicalGapsAsPartial() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        when(scanner.report(any())).thenReturn(reportWithGaps(3, false));
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().status()).isEqualTo("PARTIAL");
            assertThat(finished.dataGaps()).anyMatch(gap -> gap.contains("3只缺口"));
            assertThat(finished.dataGaps()).anyMatch(gap -> gap.contains("K线复核") && gap.contains("缺口2只"));
        } finally { service.shutdown(); }
    }

    @Test
    void treatsIntentionalMarketRiskStopAsACompleteZeroCandidateResult() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        when(scanner.report(any())).thenReturn(reportWithGaps(0, true));
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.NO_TRADE,
                Duration.ofMinutes(5));
        try {
            ShortTermScanJobStatus finished = awaitFinished(service, service.start(null).jobId());
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().status()).isEqualTo("READY");
            assertThat(finished.dataGaps()).isEmpty();
            assertThat(saved.getValue().report()).isNotNull();
        } finally { service.shutdown(); }
    }

    @Test
    void retainsCachePreviewWarningWithThePersistedReport() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ShortTermReport report = sampleReport();
        when(scanner.report(any())).thenReturn(report);
        ShortTermScanJobService service = cachedService(scanner, cache, ShortTermSnapshotStatus.CACHE_PREVIEW,
                Duration.ofMinutes(5));
        try {
            awaitFinished(service, service.start(null).jobId());
            ArgumentCaptor<ShortTermDailySnapshot> saved = ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().report()).isSameAs(report);
            assertThat(saved.getValue().status()).isEqualTo("PARTIAL");
            assertThat(saved.getValue().dataGaps()).contains("右侧CACHE_PREVIEW");
            assertThat(saved.getValue().message()).contains("右侧CACHE_PREVIEW");
        } finally { service.shutdown(); }
    }

    @Test
    void permitsTimeoutAndStatusReadsWhileHistoryArchivalIsBlocked() throws Exception {
        ShortTermService scanner = mock(ShortTermService.class);
        ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
        ResearchHistoryService history = mock(ResearchHistoryService.class);
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        when(scanner.report(any())).thenReturn(sampleReport());
        when(gate.evaluateManual(any(), any())).thenReturn(new ShortTermFinalResultGate.Result(
                ShortTermSnapshotStatus.NO_TRADE, "无合格候选", List.of()));
        CountDownLatch archiving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            archiving.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate a blocked database operation. */ }
            }
            return null;
        }).when(history).recordShortTermReport(any());
        ShortTermScanJobService service = new ShortTermScanJobService(scanner, history, tradingClock, gate,
                null, cache, Clock.fixed(Instant.parse("2026-07-23T06:52:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(100));
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String jobId = service.start(null).jobId();
            assertThat(archiving.await(2, TimeUnit.SECONDS)).isTrue();
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ShortTermScanJobStatus> status = new AtomicReference<>();
            reader.submit(() -> {
                try { status.set(awaitFinished(service, jobId)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { observed.countDown(); }
            });
            assertThat(observed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(status.get().status()).isEqualTo("FAILED");
            assertThat(status.get().message()).contains("超时");
            release.countDown();
            service.shutdown();
            Field workerField = ShortTermScanJobService.class.getDeclaredField("executorService");
            workerField.setAccessible(true);
            assertThat(((java.util.concurrent.ExecutorService) workerField.get(service))
                    .awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            verify(cache, never()).save(any());
        } finally {
            release.countDown(); service.shutdown(); reader.shutdownNow();
        }
    }

    private ShortTermReport reportWithGaps(int missingQuotes, boolean marketRiskStop) {
        ShortTermReport report = sampleReport();
        ShortTermCoverageSnapshot coverage = new ShortTermCoverageSnapshot(100, 100 - missingQuotes,
                missingQuotes, new BigDecimal("0.97"), true, "测试行情", Instant.parse("2026-07-23T06:51:00Z"));
        ShortTermMarketRegime regime = new ShortTermMarketRegime(marketRiskStop ? "RISK_OFF" : "TREND_EXPANSION",
                "测试状态", null, null, null, null, null, null, 100, "NO_TRADE", "测试说明", List.of());
        return new ShortTermReport(report.scope(), report.universeCount(), report.reviewedCount(),
                report.klineReviewedCount(), report.candidateCount(), report.quoteNote(), report.tradingSession(),
                report.methodology(), report.ruleSet(), report.weightProfile(), report.candidates(), report.hotDirections(),
                report.marketSentiment(), report.marketFundDirection(), report.exclusions(), report.tradeCaptureTokens(),
                coverage, report.reviewedSymbols(), report.dataCutoffAt(), report.generatedAt(),
                ShortTermTechnicalReviewCoverage.of(5, 4, 3), report.crossSectionContext(), regime);
    }

    private ShortTermScanJobService cachedService(ShortTermService scanner,
                                                   ShortTermScanResultCache cache, ShortTermSnapshotStatus result,
                                                   Duration scanTimeout) {
        Instant now = Instant.parse("2026-07-23T06:52:00Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        when(gate.evaluateManual(any(), any())).thenReturn(new ShortTermFinalResultGate.Result(
                result, "右侧" + result, result == ShortTermSnapshotStatus.DATA_BLOCKED ? List.of("覆盖不足") : List.of()));
        return new ShortTermScanJobService(scanner, null, tradingClock, gate, null, cache,
                Clock.fixed(now, ZoneOffset.UTC), scanTimeout);
    }

    private ShortTermScanJobStatus awaitFinished(ShortTermScanJobService service, String jobId) throws InterruptedException {
        for (int index = 0; index < 40; index++) {
            ShortTermScanJobStatus status = service.get(jobId);
            if (!"RUNNING".equals(status.status())) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("scan job did not finish in time");
    }

    private Duration configuredTimeout(ShortTermScanJobService service) throws Exception {
        Field field = ShortTermScanJobService.class.getDeclaredField("scanTimeout");
        field.setAccessible(true);
        return (Duration) field.get(service);
    }

    private ShortTermScanJobService service(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService
    ) {
        return service(shortTermService, researchHistoryService, Duration.ofMinutes(5));
    }

    private ShortTermScanJobService service(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            Duration timeout
    ) {
        Instant now = Instant.parse("2026-07-23T06:52:00Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        when(gate.evaluateManual(any(), any())).thenReturn(new ShortTermFinalResultGate.Result(
                ShortTermSnapshotStatus.DATA_BLOCKED,
                "手动扫描不在当日尾盘决策窗口，结果不可执行",
                List.of("MANUAL_OUTSIDE_DECISION_WINDOW")
        ));
        return new ShortTermScanJobService(
                shortTermService,
                researchHistoryService,
                tradingClock,
                gate,
                Clock.fixed(now, ZoneOffset.UTC),
                timeout
        );
    }

    private ShortTermReport sampleReport() {
        return new ShortTermReport(
                "A 股短线右侧启动池",
                10,
                3,
                2,
                0,
                "测试",
                new TradingSessionSnapshot(
                        "CLOSED",
                        "非交易时段",
                        false,
                        false,
                        false,
                        "休市",
                        List.of("测试规则"),
                        List.of()
                ),
                List.of("测试方法"),
                new ShortTermRuleSet(
                        120,
                        20,
                        new BigDecimal("100000000"),
                        new BigDecimal("1.20"),
                        new BigDecimal("3.50"),
                        new BigDecimal("7.00"),
                        new BigDecimal("60")
                ),
                new ShortTermWeightProfile(
                        new BigDecimal("0.30"),
                        new BigDecimal("0.25"),
                        new BigDecimal("0.35"),
                        new BigDecimal("0.40"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.15"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.05")
                ),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}
