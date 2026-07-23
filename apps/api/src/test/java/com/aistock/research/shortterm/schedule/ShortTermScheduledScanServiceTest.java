package com.aistock.research.shortterm.schedule;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.FINAL;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.PRESELECT;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.READINESS_GUARD;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.DATA_BLOCKED;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FAILED;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FINAL_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.NO_TRADE;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.PRESELECT_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermScheduledScanServiceTest {

    private static final ZoneId ZONE = TradingClockService.CHINA_MARKET_ZONE;
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 23);
    private static final Instant NOW = Instant.parse("2026-07-23T06:50:00Z");
    private static final Duration STALE_TIMEOUT = Duration.ofMinutes(5);
    private static final ShortTermScanRequest REQUEST = new ShortTermScanRequest(
            3, 6000, 60, new BigDecimal("80000000"), new BigDecimal("100"),
            new BigDecimal("15"), new BigDecimal("1.15"), new BigDecimal("4"),
            new BigDecimal("8"), new BigDecimal("58"));
    private static final OvernightRuleSet RULES = new OvernightRuleSet(
            LocalTime.of(14, 45), LocalTime.of(14, 56, 59), LocalTime.of(14, 50),
            2, new BigDecimal("0.3333"), new BigDecimal("0.50"),
            new BigDecimal("2.5"), new BigDecimal("4.0"),
            new BigDecimal("4.5"), new BigDecimal("7.0"),
            new BigDecimal("2.5"), new BigDecimal("4.5"), new BigDecimal("2.0"));

    private ShortTermAutomationSettings settings;
    private TradingClockService tradingClock;
    private ShortTermScheduledSnapshotStore store;
    private ShortTermService shortTermService;
    private ResearchHistoryService historyService;
    private RecommendationAttestationService attestationService;
    private ExecutorService executor;
    private Clock clock;
    private ShortTermScheduledScanService service;

    @BeforeEach
    void setUp() {
        settings = mock(ShortTermAutomationSettings.class);
        tradingClock = mock(TradingClockService.class);
        store = mock(ShortTermScheduledSnapshotStore.class);
        shortTermService = mock(ShortTermService.class);
        historyService = mock(ResearchHistoryService.class);
        attestationService = mock(RecommendationAttestationService.class);
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        clock = Clock.fixed(NOW, ZONE);

        when(settings.enabled()).thenReturn(true);
        when(settings.scanRequest()).thenReturn(REQUEST);
        when(settings.overnightRules()).thenReturn(RULES);
        when(settings.zone()).thenReturn(ZONE.getId());
        when(settings.freshness()).thenReturn(Duration.ofSeconds(180));
        when(settings.finalDeadline()).thenReturn(LocalTime.of(14, 53, 59));
        when(tradingClock.currentMarketDate()).thenReturn(TRADE_DATE);
        when(tradingClock.isMarketClosedDay(TRADE_DATE)).thenReturn(false);

        service = new ShortTermScheduledScanService(
                settings, tradingClock, store, shortTermService, historyService,
                attestationService, new ObjectMapper().findAndRegisterModules(),
                executor, clock, STALE_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void publishesPreselectionWithCanonicalParametersAndSha256Fingerprint() throws Exception {
        ShortTermReport report = preselectReport(List.of("600795", "601918"));
        when(shortTermService.report(REQUEST)).thenReturn(report);
        when(store.claim(eq(TRADE_DATE), eq(PRESELECT), any(), any(), eq(NOW)))
                .thenAnswer(invocation -> Optional.of(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":PRESELECT:" + invocation.getArgument(2), 1)));

        service.runNow(PRESELECT);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> parametersJson = ArgumentCaptor.forClass(String.class);
        verify(store).claim(eq(TRADE_DATE), eq(PRESELECT), fingerprint.capture(),
                parametersJson.capture(), eq(NOW));
        assertThat(fingerprint.getValue()).hasSize(64)
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(parametersJson.getValue().getBytes(StandardCharsets.UTF_8))));
        assertThat(parametersJson.getValue()).contains("\"overnightRules\"", "\"scanRequest\"");
        verify(store).finish(any(), eq(PRESELECT_READY), eq(report), eq(report.dataCutoffAt()),
                eq(NOW), eq("盘中预选已就绪"), eq(List.of()));
    }

    @Test
    void publishesFinalUnderClaimFenceBeforeAttestationAndIdempotentHistory() {
        stubClaim(FINAL, 1);
        stubReadyPreselection();
        ShortTermReport raw = finalReport(List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(30),
                reliableCoverage("0.96"));
        ShortTermReport attested = finalReport(raw.candidates(), raw.dataCutoffAt(), raw.coverage());
        when(shortTermService.finalReport(eq(REQUEST), eq(Set.of("600795")))).thenReturn(raw);
        when(attestationService.attest(raw)).thenReturn(attested);

        service.runNow(FINAL);

        String snapshotKey = TRADE_DATE + ":FINAL:" + service.resolvedParameters().fingerprint();
        InOrder order = inOrder(store, attestationService, historyService);
        order.verify(store).finish(
                eq(new ShortTermSnapshotClaim(snapshotKey, 1)), eq(FINAL_READY), eq(raw),
                eq(raw.dataCutoffAt()), eq(NOW), eq("尾盘最终结果已就绪"), eq(List.of()));
        order.verify(attestationService).attest(raw);
        order.verify(historyService).recordShortTermReport(snapshotKey, attested);
    }

    @Test
    void retainsFinalReadyWhenPostTerminalHistoryArchivalFails() {
        stubClaim(FINAL, 1);
        stubReadyPreselection();
        ShortTermReport raw = finalReport(List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(30),
                reliableCoverage("0.96"));
        ShortTermReport attested = finalReport(raw.candidates(), raw.dataCutoffAt(), raw.coverage());
        when(shortTermService.finalReport(eq(REQUEST), eq(Set.of("600795")))).thenReturn(raw);
        when(attestationService.attest(raw)).thenReturn(attested);
        doThrow(new IllegalStateException("history unavailable"))
                .when(historyService).recordShortTermReport(any(String.class), eq(attested));

        service.runNow(FINAL);

        verify(store).finish(any(), eq(FINAL_READY), eq(raw), eq(raw.dataCutoffAt()),
                eq(NOW), eq("尾盘最终结果已就绪"), eq(List.of()));
        verify(store, never()).fail(any(), any(), any(), anyList());
        verify(store, never()).finish(any(), eq(DATA_BLOCKED), any(), any(), any(), any(), anyList());
    }

    @Test
    void publishesNoTradeInsteadOfInventingCandidate() {
        stubClaim(FINAL, 1);
        stubReadyPreselection();
        ShortTermReport report = finalReport(List.of(), NOW.minusSeconds(30), reliableCoverage("0.95"));
        when(shortTermService.finalReport(eq(REQUEST), eq(Set.of("600795")))).thenReturn(report);

        service.runNow(FINAL);

        verify(store).finish(any(), eq(NO_TRADE), eq(report), eq(report.dataCutoffAt()),
                eq(NOW), eq("全部执行闸门通过，今日无合格候选"), eq(List.of()));
        verify(attestationService, never()).attest(any(ShortTermReport.class));
        verify(historyService, never()).recordShortTermReport(any());
    }

    @Test
    void blocksFinalForMissingPreselectionWithoutFetchingMarket() {
        stubClaim(FINAL, 1);
        when(store.find(eq(TRADE_DATE), eq(PRESELECT), any())).thenReturn(Optional.empty());

        service.runNow(FINAL);

        verify(shortTermService, never()).finalReport(any(), anySet());
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("缺少当日有效预选结果"), eq(List.of("PRESELECT_MISSING")));
    }

    @Test
    void blocksFinalForEmptyReviewedSymbols() {
        stubClaim(FINAL, 1);
        ShortTermScheduledSnapshot preselect = snapshot(
                PRESELECT, PRESELECT_READY, preselectReport(List.of()), NOW.minusSeconds(60), NOW.minusSeconds(60));
        when(store.find(eq(TRADE_DATE), eq(PRESELECT), any())).thenReturn(Optional.of(preselect));

        service.runNow(FINAL);

        verify(shortTermService, never()).finalReport(any(), anySet());
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("当日预选复核股票为空"), eq(List.of("PRESELECT_EMPTY")));
    }

    @Test
    void blocksFinalForCoverageCutoffFreshnessAndDeadlineFailures() {
        assertFinalBlocked(
                finalReport(List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(20),
                        reliableCoverage("0.89")),
                "COVERAGE_BELOW_90");
        assertFinalBlocked(
                finalReport(List.of(mock(ShortTermCandidate.class)),
                        Instant.parse("2026-07-22T06:50:00Z"), reliableCoverage("0.95")),
                "CUTOFF_WRONG_DATE");
        assertFinalBlocked(
                finalReport(List.of(mock(ShortTermCandidate.class)), NOW.plusSeconds(1),
                        reliableCoverage("0.95")),
                "CUTOFF_AFTER_DECISION");
        assertFinalBlocked(
                finalReport(List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(181),
                        reliableCoverage("0.95")),
                "QUOTE_STALE");

        Clock lateClock = Clock.fixed(Instant.parse("2026-07-23T06:54:00Z"), ZONE);
        ShortTermScheduledScanService lateService = new ShortTermScheduledScanService(
                settings, tradingClock, store, shortTermService, historyService,
                attestationService, new ObjectMapper().findAndRegisterModules(),
                executor, lateClock, STALE_TIMEOUT);
        when(store.claim(eq(TRADE_DATE), eq(FINAL), any(), any(), eq(lateClock.instant())))
                .thenAnswer(invocation -> Optional.of(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":FINAL:" + invocation.getArgument(2), 10)));
        lateService.runNow(FINAL);
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null),
                eq(lateClock.instant()), eq("尾盘终选已超过完成截止时间"),
                eq(List.of("FINAL_DEADLINE_EXPIRED")));
    }

    @Test
    void duplicateClaimDoesNotQueueOrFetchAgain() {
        when(store.claim(eq(TRADE_DATE), eq(PRESELECT), any(), any(), eq(NOW)))
                .thenReturn(Optional.empty());
        when(store.find(eq(TRADE_DATE), eq(PRESELECT), any())).thenReturn(Optional.empty());

        service.runNow(PRESELECT);

        verify(shortTermService, never()).report(any(ShortTermScanRequest.class));
        verify(store, never()).finish(any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void recoversPersistedStaleRunningGenerationBeforeExecuting() {
        when(store.claim(eq(TRADE_DATE), eq(PRESELECT), any(), any(), eq(NOW)))
                .thenReturn(Optional.empty());
        ShortTermScheduledSnapshot stale = snapshot(
                PRESELECT, RUNNING, null, NOW.minus(STALE_TIMEOUT).minusSeconds(1), null);
        when(store.find(eq(TRADE_DATE), eq(PRESELECT), any())).thenReturn(Optional.of(stale));
        when(store.recoverStaleRunning(
                eq(TRADE_DATE), eq(PRESELECT), any(), eq(stale.attemptCount()),
                eq(NOW.minus(STALE_TIMEOUT)), eq(NOW)))
                .thenReturn(Optional.of(new ShortTermSnapshotClaim(stale.snapshotKey(), 2)));
        ShortTermReport report = preselectReport(List.of("600795"));
        when(shortTermService.report(REQUEST)).thenReturn(report);

        service.runNow(PRESELECT);

        verify(shortTermService).report(REQUEST);
        verify(store).finish(
                eq(new ShortTermSnapshotClaim(stale.snapshotKey(), 2)),
                eq(PRESELECT_READY), eq(report), eq(report.dataCutoffAt()), eq(NOW),
                eq("盘中预选已就绪"), eq(List.of()));
    }

    @Test
    void startupRecoversStaleSameDayPreselectUsingPersistedGeneration() {
        ResolvedParametersFixture parameters = resolvedParametersFixture();
        ShortTermScheduledSnapshot stale = new ShortTermScheduledSnapshot(
                TRADE_DATE + ":PRESELECT:" + parameters.fingerprint(), TRADE_DATE, PRESELECT,
                RUNNING, 4, parameters.fingerprint(), parameters.json(), null,
                NOW.minus(STALE_TIMEOUT).minusSeconds(1), null, "正在执行", List.of(), null);
        when(store.running(TRADE_DATE, PRESELECT)).thenReturn(List.of(stale));
        when(store.running(TRADE_DATE, FINAL)).thenReturn(List.of());
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.empty());
        when(store.recoverStaleRunning(
                eq(TRADE_DATE), eq(PRESELECT), eq(parameters.fingerprint()), eq(4),
                eq(NOW.minus(STALE_TIMEOUT)), eq(NOW)))
                .thenReturn(Optional.of(new ShortTermSnapshotClaim(stale.snapshotKey(), 5)));
        ShortTermReport report = preselectReport(List.of("600795"));
        when(shortTermService.report(REQUEST)).thenReturn(report);

        service.recoverCurrentDayAfterStartup();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(store).finish(
                        eq(new ShortTermSnapshotClaim(stale.snapshotKey(), 5)),
                        eq(PRESELECT_READY), eq(report), eq(report.dataCutoffAt()), eq(NOW),
                        eq("盘中预选已就绪"), eq(List.of())));
        verify(store, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void startupRecoversStaleSameDayFinalAndRetriesFinalReadyArchivalIdempotently() {
        ResolvedParametersFixture parameters = resolvedParametersFixture();
        ShortTermScheduledSnapshot staleFinal = new ShortTermScheduledSnapshot(
                TRADE_DATE + ":FINAL:" + parameters.fingerprint(), TRADE_DATE, FINAL,
                RUNNING, 2, parameters.fingerprint(), parameters.json(), null,
                NOW.minus(STALE_TIMEOUT).minusSeconds(1), null, "正在执行", List.of(), null);
        ShortTermReport preselectReport = preselectReport(List.of("600795"));
        when(store.running(TRADE_DATE, PRESELECT)).thenReturn(List.of());
        when(store.running(TRADE_DATE, FINAL)).thenReturn(List.of(staleFinal));
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(staleFinal));
        when(store.recoverStaleRunning(
                eq(TRADE_DATE), eq(FINAL), eq(parameters.fingerprint()), eq(2),
                eq(NOW.minus(STALE_TIMEOUT)), eq(NOW)))
                .thenReturn(Optional.of(new ShortTermSnapshotClaim(staleFinal.snapshotKey(), 3)));
        when(store.find(TRADE_DATE, PRESELECT, parameters.fingerprint())).thenReturn(Optional.of(
                new ShortTermScheduledSnapshot(
                        TRADE_DATE + ":PRESELECT:" + parameters.fingerprint(), TRADE_DATE, PRESELECT,
                        PRESELECT_READY, 1, parameters.fingerprint(), parameters.json(),
                        preselectReport.dataCutoffAt(),
                        NOW.minusSeconds(120), NOW.minusSeconds(60), "ready", List.of(), preselectReport)));
        ShortTermReport finalReport = finalReport(
                List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(30), reliableCoverage("0.96"));
        when(shortTermService.finalReport(REQUEST, Set.of("600795"))).thenReturn(finalReport);
        when(attestationService.attest(finalReport)).thenReturn(finalReport);

        service.recoverCurrentDayAfterStartup();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(historyService).recordShortTermReport(staleFinal.snapshotKey(), finalReport));
        verify(store, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void startupRetriesFinalReadyArchivalWithoutMarketFetchOrTerminalRewrite() {
        ShortTermReport report = finalReport(
                List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(30),
                reliableCoverage("0.96"));
        ShortTermScheduledSnapshot finalReady = snapshot(
                FINAL, FINAL_READY, report, NOW.minusSeconds(60), NOW.minusSeconds(10));
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(finalReady));
        when(store.running(TRADE_DATE, PRESELECT)).thenReturn(List.of());
        when(store.running(TRADE_DATE, FINAL)).thenReturn(List.of());
        when(attestationService.attest(report)).thenReturn(report);

        service.recoverCurrentDayAfterStartup();

        verify(historyService).recordShortTermReport(finalReady.snapshotKey(), report);
        verify(shortTermService, never()).report(any());
        verify(shortTermService, never()).finalReport(any(), anySet());
        verify(store, never()).finish(any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void startupDoesNotRecoverRunningSnapshotsAfterFinalDeadline() {
        Clock lateClock = Clock.fixed(Instant.parse("2026-07-23T06:54:00Z"), ZONE);
        ShortTermScheduledScanService lateService = new ShortTermScheduledScanService(
                settings, tradingClock, store, shortTermService, historyService,
                attestationService, new ObjectMapper().findAndRegisterModules(),
                executor, lateClock, STALE_TIMEOUT);
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.empty());

        lateService.recoverCurrentDayAfterStartup();

        verify(store, never()).running(any(), any());
        verify(store, never()).recoverStaleRunning(
                any(LocalDate.class), any(ShortTermSnapshotStage.class), any(String.class),
                any(Integer.class), any(Instant.class), any(Instant.class));
    }

    @Test
    void finalDeadlineAlwaysUsesShanghaiEvenIfNacosContainsUtc() {
        Clock threePmShanghai = Clock.fixed(Instant.parse("2026-07-23T07:00:00Z"), ZONE);
        when(settings.zone()).thenReturn("UTC");
        ShortTermScheduledScanService fixedZoneService = new ShortTermScheduledScanService(
                settings, tradingClock, store, shortTermService, historyService,
                attestationService, new ObjectMapper().findAndRegisterModules(),
                executor, threePmShanghai, STALE_TIMEOUT);
        when(store.claim(eq(TRADE_DATE), eq(FINAL), any(), any(), eq(threePmShanghai.instant())))
                .thenAnswer(invocation -> Optional.of(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":FINAL:" + invocation.getArgument(2), 1)));

        fixedZoneService.runNow(FINAL);

        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null),
                eq(threePmShanghai.instant()), eq("尾盘终选已超过完成截止时间"),
                eq(List.of("FINAL_DEADLINE_EXPIRED")));
        verify(shortTermService, never()).finalReport(any(), anySet());
    }

    @Test
    void skipsDisabledAndWeekendRunsWithoutClaiming() {
        when(settings.enabled()).thenReturn(false);
        service.runNow(PRESELECT);
        verify(store, never()).claim(any(), any(), any(), any(), any());

        when(settings.enabled()).thenReturn(true);
        when(tradingClock.isMarketClosedDay(TRADE_DATE)).thenReturn(true);
        service.runNow(PRESELECT);
        verify(store, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void readinessGuardDoesNotFetchMarketAndPublishesExactFailureReason() {
        stubClaim(READINESS_GUARD, 1);
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.empty());

        service.runNow(READINESS_GUARD);

        verify(shortTermService, never()).report(any(ShortTermScanRequest.class));
        verify(shortTermService, never()).finalReport(any(), anySet());
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("尾盘最终快照缺失"), eq(List.of("FINAL_MISSING")));
    }

    @Test
    void readinessGuardPreservesValidFinalOrNoTradeWithoutMarketFetch() {
        for (ShortTermSnapshotStatus status : List.of(FINAL_READY, NO_TRADE)) {
            org.mockito.Mockito.reset(store);
            stubClaim(READINESS_GUARD, status == FINAL_READY ? 1 : 2);
            ShortTermReport report = finalReport(
                    status == NO_TRADE ? List.of() : List.of(mock(ShortTermCandidate.class)),
                    NOW.minusSeconds(20), reliableCoverage("0.95"));
            when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(
                    snapshot(FINAL, status, report, NOW.minusSeconds(60), NOW.minusSeconds(10))));

            service.runNow(READINESS_GUARD);

            verify(store).finish(any(), eq(status), eq(report), eq(report.dataCutoffAt()), eq(NOW),
                    eq("尾盘最终快照通过就绪检查"), eq(List.of()));
        }
        verify(shortTermService, never()).report(any(ShortTermScanRequest.class));
        verify(shortTermService, never()).finalReport(any(), anySet());
    }

    @Test
    void readinessGuardAtFourteenFiftyFourUsesOriginalFinalCompletionForDeadline() {
        Clock guardClock = Clock.fixed(Instant.parse("2026-07-23T06:54:00Z"), ZONE);
        ShortTermScheduledScanService guardService = new ShortTermScheduledScanService(
                settings, tradingClock, store, shortTermService, historyService,
                attestationService, new ObjectMapper().findAndRegisterModules(),
                executor, guardClock, STALE_TIMEOUT);
        when(store.claim(eq(TRADE_DATE), eq(READINESS_GUARD), any(), any(), eq(guardClock.instant())))
                .thenAnswer(invocation -> Optional.of(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":READINESS_GUARD:" + invocation.getArgument(2), 1)));
        ShortTermReport report = finalReport(
                List.of(mock(ShortTermCandidate.class)),
                Instant.parse("2026-07-23T06:52:30Z"), reliableCoverage("0.95"));
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(
                snapshot(
                        FINAL, FINAL_READY, report, Instant.parse("2026-07-23T06:48:00Z"),
                        Instant.parse("2026-07-23T06:53:00Z"))));

        guardService.runNow(READINESS_GUARD);

        verify(store).finish(any(), eq(FINAL_READY), eq(report), eq(report.dataCutoffAt()),
                eq(guardClock.instant()), eq("尾盘最终快照通过就绪检查"), eq(List.of()));
    }

    @Test
    void readinessGuardUsesLatestSameDayFinalWhenNacosFingerprintChanged() {
        stubClaim(READINESS_GUARD, 1);
        ShortTermReport report = finalReport(
                List.of(mock(ShortTermCandidate.class)), NOW.minusSeconds(20),
                reliableCoverage("0.95"));
        ShortTermScheduledSnapshot priorConfigurationFinal = new ShortTermScheduledSnapshot(
                TRADE_DATE + ":FINAL:prior-fingerprint", TRADE_DATE, FINAL, FINAL_READY, 1,
                "prior-fingerprint", "{}", report.dataCutoffAt(),
                NOW.minusSeconds(60), NOW.minusSeconds(10),
                "旧配置终选有效", List.of(), report);
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(priorConfigurationFinal));

        service.runNow(READINESS_GUARD);

        verify(store, never()).find(eq(TRADE_DATE), eq(FINAL), any());
        verify(store).finish(any(), eq(FINAL_READY), eq(report), eq(report.dataCutoffAt()), eq(NOW),
                eq("尾盘最终快照通过就绪检查"), eq(List.of()));
    }

    @Test
    void finalEvidenceFetchFailurePublishesDataBlockedInsteadOfFailedOrInventedCandidate() {
        stubClaim(FINAL, 1);
        stubReadyPreselection();
        when(shortTermService.finalReport(eq(REQUEST), eq(Set.of("600795"))))
                .thenThrow(new IllegalStateException("东方财富批量行情数据获取失败"));

        service.runNow(FINAL);

        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("尾盘证据获取失败：东方财富批量行情数据获取失败"),
                eq(List.of("EVIDENCE_UNAVAILABLE")));
        verify(store, never()).fail(any(), any(), any(), anyList());
        verify(attestationService, never()).attest(any(ShortTermReport.class));
        verify(historyService, never()).recordShortTermReport(any());
    }

    @Test
    void readinessGuardClassifiesFailedAndStaleFinalExactly() {
        stubClaim(READINESS_GUARD, 1);
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(
                snapshot(FINAL, FAILED, null, NOW.minusSeconds(60), NOW.minusSeconds(20))));
        service.runNow(READINESS_GUARD);
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("尾盘最终快照执行失败"), eq(List.of("FINAL_FAILED")));

        org.mockito.Mockito.reset(store);
        stubClaim(READINESS_GUARD, 2);
        ShortTermReport stale = finalReport(List.of(), NOW.minusSeconds(181), reliableCoverage("0.95"));
        when(store.latest(TRADE_DATE, FINAL)).thenReturn(Optional.of(
                snapshot(FINAL, NO_TRADE, stale, NOW.minusSeconds(60), NOW.minusSeconds(10))));
        service.runNow(READINESS_GUARD);
        verify(store).finish(any(), eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("尾盘最终快照已经过期"), eq(List.of("FINAL_STALE")));
    }

    @Test
    void executorSaturationPersistsBlockedFinalInsteadOfSilentlyDroppingIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.claim(eq(TRADE_DATE), any(), any(), any(), eq(NOW)))
                .thenAnswer(invocation -> {
                    ShortTermSnapshotStage stage = invocation.getArgument(1);
                    String fingerprint = invocation.getArgument(2);
                    return Optional.of(new ShortTermSnapshotClaim(
                            TRADE_DATE + ":" + stage + ":" + fingerprint,
                            stage == FINAL ? 3 : stage == READINESS_GUARD ? 2 : 1));
                });
        when(shortTermService.report(REQUEST)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return preselectReport(List.of("600795"));
        });

        service.submit(PRESELECT);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        service.submit(READINESS_GUARD);
        service.submit(FINAL);

        verify(store).finish(
                eq(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":FINAL:" + service.resolvedParameters().fingerprint(), 3)),
                eq(DATA_BLOCKED), eq(null), eq(null), eq(NOW),
                eq("短线定时执行器队列已满"), eq(List.of("SCHEDULED_EXECUTOR_SATURATED")));
        release.countDown();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(shortTermService).report(REQUEST));
    }

    private void assertFinalBlocked(ShortTermReport report, String reason) {
        org.mockito.Mockito.reset(store, shortTermService, attestationService, historyService);
        stubClaim(FINAL, 1);
        stubReadyPreselection();
        when(shortTermService.finalReport(eq(REQUEST), eq(Set.of("600795")))).thenReturn(report);

        service.runNow(FINAL);

        verify(store).finish(any(), eq(DATA_BLOCKED), eq(report), eq(report.dataCutoffAt()), eq(NOW),
                any(), eq(List.of(reason)));
        verify(attestationService, never()).attest(any(ShortTermReport.class));
        verify(historyService, never()).recordShortTermReport(any());
    }

    private void stubClaim(ShortTermSnapshotStage stage, int attempt) {
        when(store.claim(eq(TRADE_DATE), eq(stage), any(), any(), eq(clock.instant())))
                .thenAnswer(invocation -> Optional.of(new ShortTermSnapshotClaim(
                        TRADE_DATE + ":" + stage + ":" + invocation.getArgument(2), attempt)));
    }

    private void stubReadyPreselection() {
        when(store.find(eq(TRADE_DATE), eq(PRESELECT), any())).thenReturn(Optional.of(
                snapshot(PRESELECT, PRESELECT_READY, preselectReport(List.of("600795")),
                        NOW.minusSeconds(120), NOW.minusSeconds(60))));
    }

    private ShortTermScheduledSnapshot snapshot(
            ShortTermSnapshotStage stage,
            ShortTermSnapshotStatus status,
            ShortTermReport report,
            Instant startedAt,
            Instant completedAt
    ) {
        return new ShortTermScheduledSnapshot(
                TRADE_DATE + ":" + stage + ":fingerprint", TRADE_DATE, stage, status, 1,
                "fingerprint", "{}", report == null ? null : report.dataCutoffAt(),
                startedAt, completedAt,
                status.name(), List.of(), report);
    }

    private ShortTermReport preselectReport(List<String> reviewedSymbols) {
        return report(List.of(), reviewedSymbols, NOW.minusSeconds(30), reliableCoverage("0.95"));
    }

    private ShortTermReport finalReport(
            List<ShortTermCandidate> candidates,
            Instant cutoff,
            ShortTermCoverageSnapshot coverage
    ) {
        return report(candidates, List.of("600795"), cutoff, coverage);
    }

    private ShortTermReport report(
            List<ShortTermCandidate> candidates,
            List<String> reviewedSymbols,
            Instant cutoff,
            ShortTermCoverageSnapshot coverage
    ) {
        return new ShortTermReport(
                "A股全市场", 6000, reviewedSymbols.size(), reviewedSymbols.size(), candidates.size(),
                "测试", null, List.of(), null, null, candidates, List.of(), null, List.of(),
                java.util.Map.of(), coverage, reviewedSymbols, cutoff, NOW);
    }

    private ShortTermCoverageSnapshot reliableCoverage(String ratio) {
        return new ShortTermCoverageSnapshot(
                6000, 5900, 100, new BigDecimal(ratio), true, "实时全市场", NOW.minusSeconds(20));
    }

    private ResolvedParametersFixture resolvedParametersFixture() {
        ShortTermScheduledScanService.ResolvedParameters parameters = service.resolvedParameters();
        return new ResolvedParametersFixture(parameters.json(), parameters.fingerprint());
    }

    private record ResolvedParametersFixture(String json, String fingerprint) {
    }
}
