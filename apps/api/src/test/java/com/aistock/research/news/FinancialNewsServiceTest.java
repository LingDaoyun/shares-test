package com.aistock.research.news;

import com.aistock.research.integration.news.FinancialNewsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialNewsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private final FinancialNewsClient client = mock(FinancialNewsClient.class);
    private final NewsAnalysisService analysis = mock(NewsAnalysisService.class);
    private final FinancialNewsRepository repository = mock(FinancialNewsRepository.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final QueueExecutor executor = new QueueExecutor();
    private FinancialNewsRepository.State saved;

    @BeforeEach
    void setup() {
        when(repository.load()).thenAnswer(call -> Optional.ofNullable(saved));
        doAnswer(call -> { saved = call.getArgument(0); return null; }).when(repository).save(any());
        when(client.fetch(anyString(), any())).thenAnswer(call -> result(call.getArgument(0), "READY", call.getArgument(1)));
    }

    @Test
    void readingCurrentNeverCollectsOrAnalyzes() {
        var service = create();
        assertThat(service.current().status()).isEqualTo("IDLE");
        service.current();
        verifyNoInteractions(client, analysis);
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void schedulesMediaEveryTenMinutesAndOfficialEveryThirtyIndependently() {
        var service = create();
        service.checkSchedule();
        executor.run();
        verify(client).fetch("MEDIA", NOW);
        verify(client).fetch("OFFICIAL", NOW);
        clock.time = NOW.plusSeconds(599);
        service.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        clock.time = NOW.plusSeconds(600);
        service.checkSchedule();
        executor.run();
        verify(client).fetch("MEDIA", clock.time);
        verify(client, times(1)).fetch(eq("OFFICIAL"), any());
        clock.time = NOW.plusSeconds(1800);
        service.checkSchedule();
        executor.run();
        verify(client).fetch("OFFICIAL", clock.time);
        verifyNoInteractions(analysis);
        assertThat(saved.officialLastAttemptAt()).isEqualTo(clock.time);
    }

    @Test
    void manualRefreshIsQueuedAndDoesNotReenterWhileRunning() {
        var service = create();
        assertThat(service.refresh().status()).isEqualTo("RUNNING");
        assertThat(service.refresh().status()).isEqualTo("RUNNING");
        service.checkSchedule();
        assertThat(executor.tasks).hasSize(1);
        verifyNoInteractions(client, analysis);
        executor.run();
        assertThat(service.current().status()).isEqualTo("READY");
        verify(client, times(2)).fetch(anyString(), any());
    }

    @Test
    void failedSourcePreservesStoriesAndItsPreviousSuccessTime() {
        var service = create();
        service.refresh(); executor.run();
        var before = service.current();
        clock.time = NOW.plusSeconds(600);
        when(client.fetch(eq("MEDIA"), any())).thenReturn(result("MEDIA", "FAILED", clock.time));
        service.checkSchedule(); executor.run();
        assertThat(service.current().items()).containsAll(before.items());
        assertThat(service.current().status()).isEqualTo("PARTIAL");
        assertThat(service.current().sources()).filteredOn(source -> source.category().equals("MEDIA"))
                .singleElement().satisfies(source -> {
                    assertThat(source.status()).isEqualTo("FAILED");
                    assertThat(source.lastAttemptAt()).isEqualTo(clock.time);
                    assertThat(source.lastSuccessAt()).isEqualTo(NOW);
                });
        service.checkSchedule();
        assertThat(executor.tasks).isEmpty();
        verifyNoInteractions(analysis);
    }

    @Test
    void successfulEmptySourceKeepsExistingItemsAndReportsAnEmptyCollection() {
        var service = create();
        service.refresh(); executor.run();
        clock.time = NOW.plusSeconds(600);
        when(client.fetch(eq("MEDIA"), any())).thenReturn(result("MEDIA", "EMPTY", clock.time));
        service.checkSchedule(); executor.run();
        assertThat(service.current().items()).hasSize(2);
        assertThat(service.current().sources()).filteredOn(s -> s.category().equals("MEDIA"))
                .singleElement().satisfies(s -> {
                    assertThat(s.status()).isEqualTo("EMPTY");
                    assertThat(s.lastSuccessAt()).isEqualTo(clock.time);
                });
    }

    @Test
    void aClientExceptionStillRunsTheOtherCategoryAndPreservesPriorSourceStatus() {
        var service = create();
        service.refresh(); executor.run();
        clock.time = NOW.plusSeconds(1800);
        when(client.fetch(eq("MEDIA"), any())).thenThrow(new IllegalStateException("private response body"));
        service.refresh(); executor.run();
        assertThat(service.current().items()).hasSize(2);
        assertThat(service.current().dataGaps().toString()).doesNotContain("private response body");
        assertThat(service.current().sources()).filteredOn(s -> s.category().equals("MEDIA"))
                .singleElement().satisfies(s -> {
                    assertThat(s.status()).isEqualTo("FAILED");
                    assertThat(s.lastSuccessAt()).isEqualTo(NOW);
                });
        verify(client).fetch("OFFICIAL", clock.time);
    }

    @Test
    void completeCollectionFailureWithoutSavedDataIsExplicitlyFailed() {
        when(client.fetch(anyString(), any())).thenThrow(new IllegalStateException("upstream failed"));
        var service = create();
        service.refresh(); executor.run();
        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().items()).isEmpty();
        assertThat(service.current().dataGaps()).hasSize(2);
    }

    @Test
    void snapshotSaveFailureDoesNotLosePreviouslySavedMessagesOrSuccessTime() {
        var service = create();
        service.refresh(); executor.run();
        var before = service.current();
        service.refresh();
        doThrow(new IllegalStateException("database offline")).when(repository).save(any());
        executor.run();
        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().items()).isEqualTo(before.items());
        assertThat(service.current().sources()).isEqualTo(before.sources());
        assertThat(service.current().completedAt()).isEqualTo(before.completedAt());
    }

    @Test
    void restoresSavedDataAndScheduleAndRecoversInterruptedRunningState() {
        var first = create();
        first.refresh(); executor.run();
        var ready = first.current();
        clearInvocations(client, analysis);
        var restarted = create();
        assertThat(restarted.current()).isEqualTo(ready);
        restarted.checkSchedule();
        verifyNoInteractions(client, analysis);
        assertThat(executor.tasks).isEmpty();
        first.refresh();
        var interrupted = create();
        assertThat(interrupted.current().status()).isEqualTo("FAILED");
        assertThat(interrupted.current().items()).isEqualTo(ready.items());
        assertThat(interrupted.current().sources()).isEqualTo(ready.sources());
        assertThat(interrupted.current().dataGaps()).anyMatch(gap -> gap.contains("重启"));
    }

    @Test
    void deduplicatesUrlsAndSameSourceTitlesButRetainsCrossSourceReports() {
        var duplicate = item("duplicate-url", "MEDIA", "新标题", "媒体甲", "https://example.com/a#fragment", NOW.toString());
        var first = item("first", "MEDIA", "消息标题", "媒体甲", "https://example.com/a", NOW.minusSeconds(20).toString());
        var sameTitle = item("same-title", "MEDIA", "新标题", "媒体甲", "https://example.com/b", NOW.minusSeconds(10).toString());
        var official = item("official", "OFFICIAL", "新标题", "官方", "https://example.gov.cn/c", "2026-11-05");
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(List.of(first, duplicate, sameTitle), List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(List.of(official), List.of()));
        var service = create();
        service.refresh(); executor.run();
        assertThat(service.current().items()).hasSize(2);
        assertThat(service.current().items()).extracting(FinancialNewsItem::source).containsExactly("媒体甲", "官方");
        assertThat(service.current().items().get(1).publishedAt()).isEqualTo("2026-11-05");
    }

    @Test
    void retainsOnlyThirtyDaysWithAtMostTwoThousandNewestItems() {
        List<FinancialNewsItem> incoming = new ArrayList<>(IntStream.range(0, 2005)
                .mapToObj(i -> item("id-" + i, "MEDIA", "标题" + i, "媒体", "https://example.com/" + i,
                        NOW.minusSeconds(i).toString())).toList());
        incoming.add(item("old", "MEDIA", "过期", "媒体", "https://example.com/old", NOW.minus(Duration.ofDays(31)).toString()));
        incoming.add(item("future", "MEDIA", "未来", "媒体", "https://example.com/future", NOW.plusSeconds(60).toString()));
        incoming.add(item("undated", "MEDIA", "日期缺失", "媒体", "https://example.com/unknown", null));
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(incoming, List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(List.of(), List.of()));
        var service = create();
        service.refresh(); executor.run();
        assertThat(service.current().items()).hasSize(2000);
        assertThat(service.current().items().get(0).id()).isEqualTo("id-0");
        assertThat(service.current().items().get(1999).id()).isEqualTo("id-1999");
        assertThat(service.current().items()).extracting(FinancialNewsItem::id).doesNotContain("old", "future", "undated");
    }

    @Test
    void retainsOlderOfficialReleaseWhenNewerMediaAlreadyFillsTheSnapshot() {
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(mediaItems(2000), List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(List.of(), List.of()));
        var service = create();
        service.refresh(); executor.run();
        assertThat(service.current().items()).hasSize(2000);

        var official = item("official", "OFFICIAL", "政策发布", "官方", "https://example.gov.cn/release",
                NOW.minus(Duration.ofDays(10)).toString());
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(List.of(official), List.of()));
        service.refresh(); executor.run();

        assertThat(service.current().items()).hasSize(2000).contains(official);
        assertThat(service.current().items()).filteredOn(news -> news.category().equals("MEDIA")).hasSize(1999);
        assertThat(service.current().items().get(1999)).isEqualTo(official);
    }

    @Test
    void reservesFiveHundredNewestOfficialItemsAndFillsTheRestByPublicationTime() {
        var official = IntStream.range(0, 600).mapToObj(i -> item("official-" + i, "OFFICIAL", "官方发布" + i,
                "官方", "https://example.gov.cn/" + i, NOW.minus(Duration.ofDays(10)).minusSeconds(i).toString())).toList();
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(mediaItems(2000), List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(official, List.of()));
        var service = create();
        service.refresh(); executor.run();

        assertThat(service.current().items()).hasSize(2000);
        assertThat(service.current().items()).filteredOn(news -> news.category().equals("OFFICIAL"))
                .containsExactlyElementsOf(official.subList(0, 500));
        assertThat(service.current().items()).filteredOn(news -> news.category().equals("MEDIA")).hasSize(1500);
        assertThat(service.current().items()).extracting(news -> Instant.parse(news.publishedAt()))
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void sparseOfficialItemsDoNotWasteCapacityAndExpiredOrDuplicateItemsUseNoReservation() {
        var official = item("official", "OFFICIAL", "政策甲", "官方", "https://example.gov.cn/a",
                NOW.minus(Duration.ofDays(10)).toString());
        var second = item("second", "OFFICIAL", "政策乙", "官方", "https://example.gov.cn/b",
                NOW.minus(Duration.ofDays(12)).toString());
        var duplicateUrl = item("duplicate-url", "OFFICIAL", "政策甲", "官方", "https://example.gov.cn/a#details", official.publishedAt());
        var duplicateTitle = item("duplicate-title", "OFFICIAL", "政策甲", "官方", "https://example.gov.cn/another",
                NOW.minus(Duration.ofDays(11)).toString());
        var expired = item("expired", "OFFICIAL", "过期政策", "官方", "https://example.gov.cn/expired",
                NOW.minus(Duration.ofDays(31)).toString());
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(mediaItems(2000), List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(
                List.of(official, second, duplicateUrl, duplicateTitle, expired), List.of()));
        var service = create();
        service.refresh(); executor.run();

        assertThat(service.current().items()).hasSize(2000);
        assertThat(service.current().items()).filteredOn(news -> news.category().equals("MEDIA")).hasSize(1998);
        assertThat(service.current().items()).filteredOn(news -> news.category().equals("OFFICIAL"))
                .extracting(FinancialNewsItem::id).containsExactly("official", "second");
        assertThat(service.current().items()).extracting(FinancialNewsItem::id)
                .doesNotContain("expired", "duplicate-url", "duplicate-title");
    }

    @Test
    void unusedCapacityRemainsAvailableToOfficialItemsBeyondTheReservation() {
        var official = IntStream.range(0, 600).mapToObj(i -> item("official-" + i, "OFFICIAL", "官方发布" + i,
                "官方", "https://example.gov.cn/" + i, NOW.minusSeconds(i).toString())).toList();
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(List.of(), List.of()));
        when(client.fetch(eq("OFFICIAL"), any())).thenReturn(new FinancialNewsFetchResult(official, List.of()));
        var service = create();
        service.refresh(); executor.run();
        assertThat(service.current().items()).containsExactlyElementsOf(official);
    }

    private List<FinancialNewsItem> mediaItems(int count) {
        return IntStream.range(0, count).mapToObj(i -> item("media-" + i, "MEDIA", "媒体消息" + i, "媒体",
                "https://example.com/media/" + i, NOW.minusSeconds(i).toString())).toList();
    }

    @Test
    void persistsSuccessfulAnalysisAndReusesItAfterRestartWithoutRecollecting() {
        var service = create();
        service.refresh(); executor.run();
        var item = service.current().items().get(0);
        var result = successfulAnalysis(item);
        when(analysis.analyze(eq(item), any())).thenReturn(result);
        assertThat(service.analyze(item.id())).isEqualTo(result);
        assertThat(saved.snapshot().analyses()).containsEntry(item.id(), result);
        clearInvocations(client);
        var restarted = create();
        assertThat(restarted.analyze(item.id())).isEqualTo(result);
        verify(analysis, times(1)).analyze(eq(item), any());
        verifyNoInteractions(client);
        assertThatThrownBy(() -> restarted.analyze("not-found")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void failedAnalysisDoesNotReplaceSuccessfulSavedResultsOrBecomeCached() {
        var service = create();
        service.refresh(); executor.run();
        var one = service.current().items().get(0);
        var two = service.current().items().get(1);
        when(analysis.analyze(eq(one), any())).thenReturn(successfulAnalysis(one));
        when(analysis.analyze(eq(two), any())).thenReturn(new NewsAnalysis(two.id(), two.summary(), List.of(), "",
                List.of(), List.of(), two.url(), null, List.of("模型调用失败")));
        service.analyze(one.id());
        assertThat(service.analyze(two.id()).analyzedAt()).isNull();
        assertThat(service.current().analyses()).containsOnlyKeys(one.id());
        service.analyze(two.id());
        verify(analysis, times(2)).analyze(eq(two), any());
    }

    @Test
    void analysisSavedDuringCollectionIsNotLostWhenRefreshFinishes() {
        var service = create();
        service.refresh(); executor.run();
        var item = service.current().items().get(0);
        when(analysis.analyze(eq(item), any())).thenReturn(successfulAnalysis(item));
        service.refresh();
        service.analyze(item.id());
        executor.run();
        assertThat(service.current().analyses()).containsKey(item.id());
    }

    @Test
    void updatedOriginalEvidenceInvalidatesPreviouslySuccessfulAnalysis() {
        var service = create();
        service.refresh(); executor.run();
        var original = service.current().items().stream().filter(item -> item.category().equals("MEDIA")).findFirst().orElseThrow();
        when(analysis.analyze(eq(original), any())).thenReturn(successfulAnalysis(original));
        service.analyze(original.id());
        var updated = new FinancialNewsItem(original.id(), original.category(), original.title(), original.source(),
                original.url(), original.publishedAt(), "来源更新了摘要中的实施范围");
        when(client.fetch(eq("MEDIA"), any())).thenReturn(new FinancialNewsFetchResult(List.of(updated), List.of()));
        service.refresh(); executor.run();
        assertThat(service.current().items()).contains(updated);
        assertThat(service.current().analyses()).doesNotContainKey(original.id());
        when(analysis.analyze(eq(updated), any())).thenReturn(successfulAnalysis(updated));
        assertThat(service.analyze(updated.id()).summary()).isEqualTo(updated.summary());
        verify(analysis).analyze(eq(updated), any());
    }

    private FinancialNewsService create() { return new FinancialNewsService(client, analysis, repository, clock, executor, true); }

    private FinancialNewsFetchResult result(String category, String status, Instant time) {
        var items = status.equals("READY") ? List.of(item(category, category, category + "消息", category,
                "https://example.com/" + category, NOW.minusSeconds(60).toString())) : List.<FinancialNewsItem>of();
        return new FinancialNewsFetchResult(items, List.of(new NewsSourceStatus(category, category, category,
                "https://example.com/" + category, status, items.size(), time, status.equals("FAILED") ? null : time,
                status.equals("FAILED") ? "获取失败" : "已检查来源")));
    }

    static FinancialNewsItem item(String id, String category, String title, String source, String url, String date) {
        return new FinancialNewsItem(id, category, title, source, url, date, "原始摘要");
    }

    private NewsAnalysis successfulAnalysis(FinancialNewsItem item) {
        return new NewsAnalysis(item.id(), item.summary(), List.of("金融"), "可能影响融资需求", List.of("后续实施"),
                List.of("影响尚待核验"), item.url(), NOW, List.of("仅依据摘要"));
    }

    private static class QueueExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void run() { assertThat(tasks).hasSize(1); tasks.remove().run(); }
    }

    private static class MutableClock extends Clock {
        Instant time;
        MutableClock(Instant time) { this.time = time; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
