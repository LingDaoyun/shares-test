package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.shortterm.ShortTermUpperShadowService;
import com.aistock.research.shortterm.ShortTermUpperShadowScan;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermTechnicalReviewCoverage;
import com.aistock.research.shortterm.ShortTermMarketRegime;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShortTermDailyScanServiceTest {
    private final ShortTermDailySnapshotStore store = mock(ShortTermDailySnapshotStore.class);
    private final ShortTermScanResultCache resultCache = mock(ShortTermScanResultCache.class);
    private final ShortTermService scanner = mock(ShortTermService.class);
    private final ShortTermUpperShadowService shadow = mock(ShortTermUpperShadowService.class);
    private ShortTermDailyScanService service(String time) {
        return new ShortTermDailyScanService(store, scanner, shadow,
                new TradingClockService(Clock.fixed(Instant.parse(time), ZoneId.of("Asia/Shanghai"))), resultCache);
    }

    @Test
    void skipsOfficialHolidaysAndDoesNotClaimAScan() {
        service("2026-10-01T06:00:00Z").scanAtTwo();
        verifyNoInteractions(store, scanner, shadow);
    }

    @Test
    void duplicateDailyTriggerDoesNotStartAnotherMarketScan() {
        when(store.claim(any())).thenReturn(false);
        service("2026-10-15T06:00:00Z").scanAtTwo();
        verifyNoInteractions(scanner, shadow);
    }

    @Test
    void loadingTheSavedResultDoesNotTriggerMarketRequests() {
        when(store.latest()).thenReturn(Optional.empty());
        assertThat(service("2026-10-15T06:00:00Z").latest().status()).isEqualTo("NOT_STARTED");
        verifyNoInteractions(scanner, shadow);
    }

    @Test
    void retryBeforeTheScheduledWindowDoesNotClaimOrScan() {
        assertThatThrownBy(() -> service("2026-10-15T05:59:00Z").retryToday())
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, scanner, shadow);
    }

    @Test
    void anUnclaimedRetryOnlyReadsSavedState() {
        when(store.claimRetry(any())).thenReturn(false);
        when(store.latest()).thenReturn(Optional.empty());
        assertThat(service("2026-10-15T06:10:00Z").retryToday().status()).isEqualTo("NOT_STARTED");
        verifyNoInteractions(scanner, shadow);
    }

    @Test
    void anAcceptedRetryReturnsRunningAndSavesItsActualStartTime() {
        when(store.claimRetry(any())).thenReturn(true);
        when(shadow.scan()).thenThrow(new IllegalStateException("source failed"));
        when(scanner.report(any(com.aistock.research.shortterm.ShortTermScanRequest.class)))
                .thenThrow(new IllegalStateException("source failed"));
        var result = service("2026-10-15T06:10:00Z").retryToday();
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.startedAt()).isEqualTo(Instant.parse("2026-10-15T06:10:00Z"));
        var captor = org.mockito.ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
        verify(store, timeout(2000)).finishAndPublish(captor.capture(), eq(resultCache));
        assertThat(captor.getValue().startedAt()).isEqualTo(result.startedAt());
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
    }

    @Test
    void failuresArePersistedInsteadOfBeingPresentedAsAnEmptySuccessfulScan() {
        when(store.claim(any())).thenReturn(true);
        when(shadow.scan()).thenThrow(new IllegalStateException("行情断开"));
        when(scanner.report(any(com.aistock.research.shortterm.ShortTermScanRequest.class)))
                .thenThrow(new IllegalStateException("扫描失败"));
        service("2026-10-15T06:00:00Z").scanAtTwo();
        var captor = org.mockito.ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
        verify(store, timeout(2000)).finishAndPublish(captor.capture(), eq(resultCache));
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().dataGaps()).hasSize(2);
    }

    @Test
    void dailyScanRequestsEveryPreselectedStockAndEveryMatchingCandidate() {
        prepareCompletedSources(0, 0, false);
        service("2026-10-15T06:00:00Z").scanAtTwo();
        var request = org.mockito.ArgumentCaptor.forClass(ShortTermScanRequest.class);
        verify(scanner, timeout(2000)).report(request.capture());
        assertThat(request.getValue().limit()).isZero();
        assertThat(request.getValue().scanLimit()).isZero();
        assertThat(request.getValue().klineLimit()).isZero();
        assertThat(request.getValue().allowStaticCachePreview()).isFalse();
        assertThat(savedResult().status()).isEqualTo("READY");
    }

    @Test
    void completedCandidatesRemainAvailableButMissingTechnicalHistoryMakesTheResultPartial() {
        var report = prepareCompletedSources(180, 172, false);
        service("2026-10-15T06:00:00Z").scanAtTwo();
        var result = savedResult();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.report()).isSameAs(report);
        assertThat(result.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("预选180", "已请求180", "有效172", "缺口8"));
    }

    @Test
    void intentionallyStoppedMarketRiskScanIsNotMistakenForAHistoryRepairFailure() {
        var report = prepareCompletedSources(180, 0, true);
        when(report.technicalReviewCoverage()).thenReturn(ShortTermTechnicalReviewCoverage.of(180, 0, 0));
        service("2026-10-15T06:00:00Z").scanAtTwo();
        assertThat(savedResult().status()).isEqualTo("READY");
        assertThat(savedResult().dataGaps()).isEmpty();
    }

    @Test
    void cronReturnsWhileItsFullScanRemainsActiveBeyondThirtyMinutes() throws Exception {
        prepareCompletedSources(0, 0, false);
        var current = new AtomicReference<>(Instant.parse("2026-10-15T06:00:00Z"));
        Clock advancingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var running = new AtomicReference<ShortTermDailySnapshot>();
        when(store.claim(any())).thenAnswer(call -> { running.set(call.getArgument(0)); return true; });
        when(store.latest()).thenAnswer(call -> Optional.ofNullable(running.get()));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(shadow.scan()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test worker not released");
            return readyUpper();
        });
        var service = new ShortTermDailyScanService(store, scanner, shadow, new TradingClockService(advancingClock), resultCache);
        Thread worker = new Thread(service::scanAtTwo);
        worker.start();
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            worker.join(500);
            assertThat(worker.isAlive()).as("the scheduled trigger must return before the scan completes").isFalse();
            current.set(Instant.parse("2026-10-15T06:31:00Z"));
            assertThat(service.latest().status()).isEqualTo("RUNNING");
            assertThat(service.retryToday()).isSameAs(running.get());
            verify(store, never()).claimRetry(any());
        } finally {
            release.countDown();
            worker.join(3000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(savedResult().status()).isEqualTo("READY");
    }

    @Test
    void anOrphanedPersistedRunStillBecomesInterruptedAfterThirtyMinutes() {
        var running = new ShortTermDailySnapshot(LocalDate.parse("2026-10-15"), Instant.parse("2026-10-15T06:00:00Z"),
                Instant.parse("2026-10-15T06:00:00Z"), null, "RUNNING", "running", null, null, List.of());
        when(store.latest()).thenReturn(Optional.of(running));
        assertThat(service("2026-10-15T06:31:00Z").latest().status()).isEqualTo("INTERRUPTED");
        verifyNoInteractions(scanner, shadow);
    }

    @Test
    void dailyCompletionHandsBothBranchesToTheTransactionalPublicationStore() {
        prepareCompletedSources(0, 0, false);
        service("2026-10-15T06:00:00Z").scanAtTwo();
        var completed = savedResult();
        assertThat(completed.status()).isEqualTo("READY");
        assertThat(completed.report()).isNotNull();
        assertThat(completed.upperShadow()).isNotNull();
        assertThat(completed.runId()).isNotBlank();
        verify(store).finishAndPublish(completed, resultCache);
        verifyNoInteractions(resultCache);
    }

    @Test
    void readingTheSharedCacheDoesNotReadDailyTaskStateOrStartScans() {
        var cached = new ShortTermDailySnapshot(LocalDate.of(2026, 10, 15), null, Instant.parse("2026-10-15T06:00:00Z"),
                Instant.parse("2026-10-15T06:01:00Z"), "PARTIAL", "manual result", null, readyUpper(), List.of(), "manual-job");
        when(resultCache.latest()).thenReturn(Optional.of(cached));
        var api = new ShortTermDailyScanController(service("2026-10-15T06:05:00Z"));
        assertThat(api.resultCache()).isSameAs(cached);
        verifyNoInteractions(store, scanner, shadow);
    }

    @Test
    void theSharedCacheEndpointReturnsNotStartedUntilOneScanFinishes() {
        when(resultCache.latest()).thenReturn(Optional.empty());
        var api = new ShortTermDailyScanController(service("2026-10-15T06:05:00Z"));
        assertThat(api.resultCache().status()).isEqualTo("NOT_STARTED");
        assertThat(api.resultCache().runId()).isNull();
        verifyNoInteractions(store, scanner, shadow);
    }

    private ShortTermReport prepareCompletedSources(int requested, int sufficient, boolean riskOff) {
        when(store.claim(any())).thenReturn(true);
        when(store.finishAndPublish(any(), eq(resultCache))).thenReturn(true);
        when(shadow.scan()).thenReturn(readyUpper());
        ShortTermReport report = mock(ShortTermReport.class);
        when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(6000, 6000, 0, BigDecimal.ONE, true, "fixture", Instant.parse("2026-10-15T06:00:00Z")));
        when(report.technicalReviewCoverage()).thenReturn(ShortTermTechnicalReviewCoverage.of(requested, requested, sufficient));
        var regime = mock(ShortTermMarketRegime.class);
        when(regime.riskOff()).thenReturn(riskOff);
        when(report.marketRegime()).thenReturn(regime);
        when(scanner.report(any(ShortTermScanRequest.class))).thenReturn(report);
        return report;
    }

    private ShortTermUpperShadowScan readyUpper() {
        return new ShortTermUpperShadowScan(LocalDate.parse("2026-10-15"), Instant.parse("2026-10-15T06:00:00Z"),
                "READY", "fixture", 6000, 6000, 0, 0, 0, 0, List.of(), List.of(), "fixture");
    }

    private ShortTermDailySnapshot savedResult() {
        var captor = org.mockito.ArgumentCaptor.forClass(ShortTermDailySnapshot.class);
        verify(store, timeout(2000)).finishAndPublish(captor.capture(), eq(resultCache));
        return captor.getValue();
    }
}
