package com.aistock.research.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyDailyServiceTest {
    private final PolicyThemeService themes = mock(PolicyThemeService.class);
    private final PolicyAiReviewService reviews = mock(PolicyAiReviewService.class);
    private final PolicyDailyRepository repository = mock(PolicyDailyRepository.class);
    private final MutableClock clock = new MutableClock("2026-10-17T00:29:00Z");
    private final QueuedExecutor executor = new QueuedExecutor();
    private PolicyDailyService service;

    @BeforeEach
    void setup() {
        when(repository.load()).thenReturn(Optional.empty());
        service = new PolicyDailyService(themes, reviews, repository, clock, executor, 8);
        when(reviews.review(any(PolicyTheme.class))).thenAnswer(call -> successfulReview());
    }

    @Test
    void reviewsAnExplicitLongTermPlanningPassageWithoutCallingItARecentCatalyst() {
        PolicyTheme original = theme("SOLID_STATE_BATTERY", "2025-01-01", 80);
        PolicyTheme planning = new PolicyTheme(original.themeCode(), original.name(), original.policyLevel(),
                original.timeHorizon(), original.strengthScore(), original.chainSegments(), List.of(new PolicySignal(
                "工业部门", "长期规划", "新型电池规划", 80, "https://policy.example/plan", "2025-01-01",
                "加快全固态电池技术研发。", true)), original.risks(), original.companyPool());
        when(themes.listThemesWithStatus()).thenReturn(collection(planning, theme("OLD_NEWS", "2025-01-01", 80)));

        service.refresh();
        executor.runNext();

        assertThat(service.current().reviewedThemeCodes()).containsExactly("SOLID_STATE_BATTERY");
        assertThat(service.current().themes().get(0).signals().get(0).publishedAt()).isEqualTo("2025-01-01");
        verify(reviews).review(planning);
        verify(reviews, times(1)).review(any(PolicyTheme.class));
    }

    @Test
    void getOnlyReadsSnapshotAndRefreshDeduplicatesWhileRunning() {
        when(themes.listThemesWithStatus()).thenReturn(collection(theme("A", "2026-10-16", 80)));
        assertThat(service.current().status()).isEqualTo("IDLE");
        verifyNoInteractions(themes, reviews);
        PolicyDailySnapshot running = service.refresh();
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(service.refresh()).isEqualTo(running);
        assertThat(executor.tasks).hasSize(1);
        verifyNoInteractions(themes, reviews);
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("READY");
        assertThat(service.current().reviewedThemeCodes()).containsExactly("A");
        verify(themes, times(1)).listThemesWithStatus();
        verify(reviews).review(any(PolicyTheme.class));
    }

    @Test
    void simultaneousRefreshRequestsEnqueueOnlyOneRun() throws Exception {
        var callers = Executors.newFixedThreadPool(6);
        try {
            var requests = IntStream.range(0, 12)
                    .mapToObj(index -> callers.submit(service::refresh)).toList();
            for (var request : requests) assertThat(request.get(5, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
            assertThat(executor.tasks).hasSize(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void publishesCollectedThemesAndReviewProgressWhileBackgroundReviewsAreStillRunning() throws Exception {
        PolicyTheme first = theme("A", "2026-10-16", 90);
        PolicyTheme second = theme("B", "2026-10-16", 80);
        when(themes.listThemesWithStatus()).thenReturn(collection(first, second));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        when(reviews.review(any(PolicyTheme.class))).thenAnswer(call -> {
            PolicyTheme theme = call.getArgument(0);
            if (theme.themeCode().equals("A")) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                secondEntered.countDown();
                assertThat(releaseSecond.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return successfulReview();
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            PolicyDailyService background = new PolicyDailyService(themes, reviews, repository, clock, worker, 8);
            background.refresh();
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            PolicyDailySnapshot collectingFinished = background.current();
            assertThat(collectingFinished.status()).isEqualTo("RUNNING");
            assertThat(collectingFinished.themes()).containsExactly(first, second);
            assertThat(collectingFinished.reviewedThemeCodes()).isEmpty();
            assertThat(collectingFinished.completedAt()).isNull();
            verify(repository).save(argThat(state -> state.snapshot().equals(collectingFinished)));

            releaseFirst.countDown();
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            PolicyDailySnapshot oneReviewed = background.current();
            assertThat(oneReviewed.status()).isEqualTo("RUNNING");
            assertThat(oneReviewed.reviewedThemeCodes()).containsExactly("A");
            assertThat(oneReviewed.dataGaps()).anyMatch(gap -> gap.contains("1/2"));
            verify(repository).save(argThat(state -> state.snapshot().equals(oneReviewed)));

            releaseSecond.countDown();
            worker.shutdown();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(background.current().status()).isEqualTo("READY");
            assertThat(background.current().reviewedThemeCodes()).containsExactly("A", "B");
            assertThat(background.current().dataGaps()).isEmpty();
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void catchesUpAfterSleepAndRunsOnlyOncePerShanghaiDayEvenOnFailure() {
        when(themes.listThemesWithStatus()).thenThrow(new IllegalStateException("network"));
        service.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        clock.set("2026-10-17T05:00:00Z");
        service.checkSchedule();
        service.checkSchedule();
        assertThat(executor.tasks).hasSize(1);
        executor.runNext();
        service.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        clock.set("2026-10-17T16:01:00Z"); // Shanghai next day, before 08:30
        service.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        clock.set("2026-10-18T00:31:00Z");
        service.checkSchedule();
        assertThat(executor.tasks).hasSize(1);
    }

    @Test
    void preservesPreviousDataDuringRefreshAndCollectionFailure() {
        PolicyTheme previous = theme("A", "2026-10-16", 80);
        when(themes.listThemesWithStatus()).thenReturn(collection(previous)).thenThrow(new IllegalStateException("secret-token"));
        service.refresh();
        executor.runNext();
        PolicyDailySnapshot old = service.current();
        clock.set("2026-10-18T01:00:00Z");
        assertThat(service.refresh().themes()).containsExactly(previous);
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().themes()).containsExactly(previous);
        assertThat(service.current().reviewedThemeCodes()).isEqualTo(old.reviewedThemeCodes());
        assertThat(service.current().completedAt()).isEqualTo(clock.instant());
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("2026-10-18"));
        assertThat(service.current().dataGaps()).noneMatch(gap -> gap.contains("secret-token"));
    }

    @Test
    void retainsLastEvidenceWhenSourcesReturnNoSignals() {
        PolicyTheme previous = theme("A", "2026-10-16", 80);
        when(themes.listThemesWithStatus()).thenReturn(collection(previous)).thenReturn(collection(withoutSignals("A")));
        service.refresh();
        executor.runNext();
        service.refresh();
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().themes()).containsExactly(previous);
    }

    @Test
    void persistsTopicsAndExplainsMissingKeyWithoutClaimingReviewed() {
        when(themes.listThemesWithStatus()).thenReturn(collection(theme("A", "2026-10-16", 80)));
        when(reviews.review(any(PolicyTheme.class))).thenReturn(PolicyAiReview.unconfigured("A", "主题A"));
        service.refresh();
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("PARTIAL");
        assertThat(service.current().themes()).hasSize(1);
        assertThat(service.current().reviewedThemeCodes()).isEmpty();
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("API Key"));
        verify(repository, atLeast(2)).save(any());
    }

    @Test
    void keepsPartialSourceCoverageVisibleEvenWhenAllAvailableThemesWereReviewed() {
        when(themes.listThemesWithStatus()).thenReturn(new PolicyThemeCollection(
                List.of(theme("A", "2026-10-16", 80)), List.of("某官方来源采集失败，本次来源覆盖不完整。")));
        service.refresh();
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("PARTIAL");
        assertThat(service.current().reviewedThemeCodes()).containsExactly("A");
        assertThat(service.current().dataGaps()).contains("某官方来源采集失败，本次来源覆盖不完整。");
        verify(repository).save(argThat(saved -> saved.snapshot().status().equals("RUNNING")
                && saved.snapshot().reviewedThemeCodes().isEmpty() && saved.snapshot().themes().size() == 1
                && saved.snapshot().dataGaps().contains("某官方来源采集失败，本次来源覆盖不完整。")));
    }

    @Test
    void skipsThemesWithoutRealEvidenceAndOldEvidenceButAllowsUnknownDates() {
        when(themes.listThemesWithStatus()).thenReturn(collection(withoutSignals("EMPTY"), theme("OLD", "2026-01-01", 99),
                theme("UNKNOWN", null, 60), theme("FUTURE", "2027-01-01", 99)));
        service.refresh();
        executor.runNext();
        assertThat(service.current().reviewedThemeCodes()).containsExactly("UNKNOWN");
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("日期"));
        verify(reviews, times(1)).review(any(PolicyTheme.class));
    }

    @Test
    void neverChargesWhenNoEvidenceExists() {
        when(themes.listThemesWithStatus()).thenReturn(collection(withoutSignals("EMPTY")));
        service.refresh();
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("PARTIAL");
        assertThat(service.current().reviewedThemeCodes()).isEmpty();
        verifyNoInteractions(reviews);
    }

    @Test
    void evaluatesOffsetDatesInShanghaiWithInclusiveNinetyDayBoundary() {
        when(themes.listThemesWithStatus()).thenReturn(collection(
                theme("BOUNDARY", "2026-07-18T16:00:00Z", 80), // July 19 in Shanghai: exactly 90 days
                theme("TOO_OLD", "2026-07-19T00:00:00+14:00", 80), // July 18 in Shanghai
                theme("FUTURE", "2026-10-17T16:00:00Z", 80), // October 18 in Shanghai
                theme("OFFSET", "2026-10-16T23:00:00-07:00", 80), // October 17 in Shanghai
                theme("LOCAL", "2026-07-19 08:00:00", 80)));
        service.refresh();
        executor.runNext();
        assertThat(service.current().reviewedThemeCodes()).containsExactlyInAnyOrder("BOUNDARY", "OFFSET", "LOCAL");
        assertThat(service.current().dataGaps()).noneMatch(gap -> gap.contains("日期未知"));
        verify(reviews, times(3)).review(any(PolicyTheme.class));
    }

    @Test
    void rejectsTimestampWithoutTrendPlanAndContinuesOtherReviews() {
        when(themes.listThemesWithStatus()).thenReturn(collection(theme("BAD", "2026-10-16", 90), theme("OK", "2026-10-16", 80)));
        PolicyAiReview invalid = mock(PolicyAiReview.class);
        when(invalid.reviewedAt()).thenReturn(clock.instant());
        when(invalid.dataGaps()).thenReturn(List.of());
        when(reviews.review(argThat((PolicyTheme theme) -> "BAD".equals(theme.themeCode())))).thenReturn(invalid);
        service.refresh();
        executor.runNext();
        assertThat(service.current().status()).isEqualTo("PARTIAL");
        assertThat(service.current().reviewedThemeCodes()).containsExactly("OK");
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("主题BAD") && gap.contains("未完成"));
    }

    @Test
    void doesNotEnqueueNetworkWorkWhenRunningStatusCannotBePersisted() {
        doThrow(new IllegalStateException("storage unavailable")).when(repository).save(any());
        clock.set("2026-10-17T01:00:00Z");
        service.checkSchedule();
        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(executor.tasks).isEmpty();
        service.checkSchedule();
        verifyNoInteractions(themes, reviews);
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("数据库"));
    }

    @Test
    void alreadySuccessfulTodayIsNotAutomaticallyRepeatedAfterRestart() {
        when(themes.listThemesWithStatus()).thenReturn(collection(theme("A", "2026-10-16", 80)));
        service.refresh();
        executor.runNext();
        var saved = org.mockito.ArgumentCaptor.forClass(PolicyDailyState.class);
        verify(repository, atLeast(2)).save(saved.capture());
        when(repository.load()).thenReturn(Optional.of(saved.getValue()));
        clock.set("2026-10-17T06:00:00Z");
        PolicyDailyService restarted = new PolicyDailyService(themes, reviews, repository, clock, executor, 8);
        restarted.checkSchedule();
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void budgetsEightThemesByScoreThenFreshnessAndUsesCollectedInstances() {
        List<PolicyTheme> collected = IntStream.range(0, 10)
                .mapToObj(index -> theme("T" + index, "2026-10-" + (10 + index), 80)).toList();
        // Keep dates before today so the test also verifies future evidence is excluded.
        clock.set("2026-10-20T01:00:00Z");
        when(themes.listThemesWithStatus()).thenReturn(new PolicyThemeCollection(collected, List.of()));
        service.refresh();
        executor.runNext();
        assertThat(service.current().reviewedThemeCodes()).hasSize(8);
        assertThat(service.current().dataGaps()).anyMatch(gap -> gap.contains("2") && gap.contains("预算"));
        InOrder order = inOrder(reviews);
        for (int index = 9; index >= 2; index--) order.verify(reviews).review(same(collected.get(index)));
        verify(themes, times(1)).listThemesWithStatus();
        verify(reviews, times(8)).review(any(PolicyTheme.class));
    }

    @Test
    void marksRestartedRunInterruptedWithoutAutomaticallyRetryingSameDay() {
        PolicyDailySnapshot running = new PolicyDailySnapshot("RUNNING", PolicyDailySnapshot.SCHEDULED_TIME,
                Instant.parse("2026-10-17T01:00:00Z"), null, List.of(theme("OLD", "2026-10-16", 80)), List.of(), List.of());
        when(repository.load()).thenReturn(Optional.of(new PolicyDailyState(running, LocalDate.of(2026, 10, 17), null)));
        clock.set("2026-10-17T02:00:00Z");
        PolicyDailyService restarted = new PolicyDailyService(themes, reviews, repository, clock, executor, 8);
        assertThat(restarted.current().status()).isEqualTo("FAILED");
        assertThat(restarted.current().dataGaps()).anyMatch(gap -> gap.contains("中断"));
        restarted.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        restarted.refresh();
        assertThat(executor.tasks).hasSize(1);
    }

    @Test
    void successfulSnapshotSurvivesServiceRestart() {
        when(themes.listThemesWithStatus()).thenReturn(collection(theme("A", "2026-10-16", 80)));
        service.refresh();
        executor.runNext();
        var saved = org.mockito.ArgumentCaptor.forClass(PolicyDailyState.class);
        verify(repository, atLeast(2)).save(saved.capture());
        when(repository.load()).thenReturn(Optional.of(saved.getValue()));
        PolicyDailyService restarted = new PolicyDailyService(themes, reviews, repository, clock, executor, 8);
        assertThat(restarted.current()).isEqualTo(service.current());
    }

    private PolicyAiReview successfulReview() {
        PolicyAiReview review = mock(PolicyAiReview.class, RETURNS_DEEP_STUBS);
        when(review.reviewedAt()).thenReturn(clock.instant());
        when(review.dataGaps()).thenReturn(List.of());
        return review;
    }

    private PolicyThemeCollection collection(PolicyTheme... themes) {
        return new PolicyThemeCollection(List.of(themes), List.of());
    }

    private PolicyTheme theme(String code, String date, int score) {
        return new PolicyTheme(code, "主题" + code, "国家级", "2-12周", BigDecimal.valueOf(score), List.of(),
                List.of(new PolicySignal("政府网", "政策", "产业发展规划", 80, "https://www.gov.cn/" + code, date)), List.of(), List.of());
    }

    private PolicyTheme withoutSignals(String code) {
        return new PolicyTheme(code, code, "国家级", "2-12周", BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(String instant) { set(instant); }
        void set(String value) { instant = Instant.parse(value); }
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
}
