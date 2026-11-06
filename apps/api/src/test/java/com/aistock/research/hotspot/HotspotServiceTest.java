package com.aistock.research.hotspot;

import com.aistock.research.integration.news.IndustryNewsClient;
import com.aistock.research.integration.news.IndustryNewsFetchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aistock.research.hotspot.HotspotTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotspotServiceTest {
    private final IndustryNewsClient newsClient = mock(IndustryNewsClient.class);
    private final HotspotAnalysisService analysis = mock(HotspotAnalysisService.class);
    private final HotspotCompanyClient companies = mock(HotspotCompanyClient.class);
    private final HotspotRepository repository = mock(HotspotRepository.class);
    private final Instant now = Instant.now().minusSeconds(60);
    private final List<HotspotService> services = new CopyOnWriteArrayList<>();
    private final List<HotspotRepository.State> saved = new CopyOnWriteArrayList<>();

    @AfterEach
    void shutdown() { services.forEach(HotspotService::shutdown); }

    @Test
    void readsSavedSnapshotWithoutFetchingAndRecoversInterruptedRefresh() {
        var source = news("source-1", now);
        var previous = snapshot(List.of(source), List.of(), now);
        var running = new HotspotSnapshot("RUNNING", previous.generatedAt(), now, now, now, now,
                "刷新中", 1, 0, previous.news(), previous.events(), List.of());
        var service = create(new HotspotRepository.State(running, "previous-hash"));

        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().generatedAt()).isEqualTo(now);
        assertThat(service.current().news()).containsExactly(source);
        assertThat(saved.get(0).analyzedNewsHash()).isEqualTo("previous-hash");
        verifyNoInteractions(newsClient, analysis, companies);
    }

    @Test
    void preventsConcurrentRefreshAndEnforcesCooldownAfterCompletion() throws Exception {
        var source = news("source-1", now);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(newsClient.fetchRecent(60)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return new IndustryNewsFetchResult(List.of(source), List.of());
        });
        when(analysis.modelIdentity()).thenReturn("model-v1");
        when(analysis.analyze(anyList(), any())).thenReturn(new HotspotAnalysisService.Result(List.of(), List.of(), true));
        when(companies.quoteIndex(anyList(), anyList())).thenReturn(Map.of());
        var service = create(null);
        try {
            service.refresh();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.refresh().status()).isEqualTo("RUNNING");
            assertThat(saved).hasSize(1);
        } finally { release.countDown(); }
        awaitFinished(service);
        var complete = service.current();
        assertThat(service.refresh()).isEqualTo(complete);
        verify(newsClient, times(1)).fetchRecent(60);
    }

    @Test
    void reusesPersistedAnalysisAcrossRestartAndUpdatesPriceWithoutOverwritingFirstObservation() {
        var source = news("source-1", now);
        var event = event("event-1", List.of(source), List.of(stock(source, now)), now);
        when(newsClient.fetchRecent(60)).thenReturn(new IndustryNewsFetchResult(List.of(source), List.of("覆盖有限")));
        when(analysis.modelIdentity()).thenReturn("model-v1");
        when(analysis.analyze(anyList(), any())).thenReturn(new HotspotAnalysisService.Result(List.of(event), List.of(), true));
        when(companies.quoteIndex(anyList(), anyList())).thenReturn(Map.of("600001", quote("600001", "样本公司", "11.00", now)));
        var first = create(null);
        first.refresh();
        awaitFinished(first);
        var persisted = saved.get(saved.size() - 1);
        first.shutdown();

        HotspotSnapshot ready = persisted.snapshot();
        var due = new HotspotSnapshot(ready.status(), ready.generatedAt(), ready.startedAt(), ready.lastAttemptAt(), now, now,
                ready.summary(), ready.newsCount(), ready.companyCount(), ready.news(), ready.events(), ready.dataGaps());
        var restarted = create(new HotspotRepository.State(due, persisted.analyzedNewsHash()));
        when(companies.quoteIndex(anyList(), anyList())).thenReturn(Map.of("600001", quote("600001", "样本公司", "12.00", now.plusSeconds(30))));
        restarted.refresh();
        awaitFinished(restarted);

        verify(analysis, times(1)).analyze(anyList(), any());
        assertThat(restarted.current().summary()).contains("新闻内容未变化");
        assertThat(restarted.current().events().get(0).recommendations()).singleElement().satisfies(stock -> {
            assertThat(stock.latestPrice()).isEqualByComparingTo("12.00");
            assertThat(stock.quoteAsOf()).isEqualTo(now.plusSeconds(30).toString());
            assertThat(stock.firstSeenPrice()).isEqualByComparingTo("10.00");
            assertThat(stock.firstSeenQuoteAsOf()).isEqualTo(now.toString());
            assertThat(stock.firstSeenAt()).isEqualTo(now);
        });
    }

    @Test
    void emptyCollectionKeepsSavedContentAndSpecificSourceFailuresWithoutCallingModel() {
        var source = news("source-1", now);
        var event = event("event-1", List.of(source), List.of(stock(source, now)), now);
        var previous = snapshot(List.of(source), List.of(event), now);
        when(newsClient.fetchRecent(60)).thenReturn(new IndustryNewsFetchResult(List.of(), List.of("36氪快讯：RSS 获取或解析失败")));
        var service = create(new HotspotRepository.State(previous, "previous-hash"));

        service.refresh();
        awaitFinished(service);

        assertThat(service.current().status()).isEqualTo("FAILED");
        assertThat(service.current().generatedAt()).isEqualTo(previous.generatedAt());
        assertThat(service.current().events()).isEqualTo(previous.events());
        assertThat(service.current().dataGaps()).contains("36氪快讯：RSS 获取或解析失败");
        assertThat(saved.get(saved.size() - 1).analyzedNewsHash()).isEqualTo("previous-hash");
        verifyNoInteractions(analysis, companies);
    }

    @Test
    void temporaryReviewFailurePreservesPreviouslyVerifiedRecommendations() {
        var source = news("source-1", now);
        var oldStock = stock(source, now);
        var previous = snapshot(List.of(source), List.of(event("event-1", List.of(source), List.of(oldStock), now)), now);
        var fresh = news("source-2", now.plusSeconds(30));
        when(newsClient.fetchRecent(60)).thenReturn(new IndustryNewsFetchResult(List.of(source, fresh), List.of()));
        when(analysis.modelIdentity()).thenReturn("model-v1");
        when(analysis.analyze(anyList(), any())).thenReturn(new HotspotAnalysisService.Result(
                List.of(event("event-2", List.of(source, fresh), List.of(), now)), List.of("股票证据复核未完成"), false));
        when(companies.quoteIndex(anyList(), anyList())).thenReturn(Map.of());
        var service = create(new HotspotRepository.State(previous, "previous-hash"));

        service.refresh();
        awaitFinished(service);

        assertThat(service.current().events()).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo("event-1");
            assertThat(event.recommendations()).containsExactly(oldStock);
            assertThat(event.sources()).containsExactly(source, fresh);
        });
        assertThat(saved.get(saved.size() - 1).analyzedNewsHash()).isEqualTo("previous-hash");
    }

    @Test
    void expiredRecommendationDoesNotSurviveBecauseEventHasNewerNews() {
        var expired = news("expired", now.minus(Duration.ofDays(8)));
        var recent = news("recent", now);
        var previous = snapshot(List.of(recent), List.of(event("event-1", List.of(expired, recent),
                List.of(stock(expired, now.minus(Duration.ofDays(8)))), now)), now);
        when(newsClient.fetchRecent(60)).thenReturn(new IndustryNewsFetchResult(List.of(recent), List.of()));
        when(analysis.modelIdentity()).thenReturn("model-v1");
        when(analysis.analyze(anyList(), any())).thenReturn(new HotspotAnalysisService.Result(List.of(), List.of(), true));
        when(companies.quoteIndex(anyList(), anyList())).thenReturn(Map.of());
        var service = create(new HotspotRepository.State(previous, "previous-hash"));

        service.refresh();
        awaitFinished(service);

        assertThat(service.current().events()).singleElement().satisfies(event -> {
            assertThat(event.sources()).containsExactly(recent);
            assertThat(event.recommendations()).isEmpty();
        });
    }

    @Test
    void failedInitialPersistenceDoesNotCollectOrSpendTokens() {
        var service = create(null);
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any());

        assertThat(service.refresh().status()).isEqualTo("FAILED");
        verifyNoInteractions(newsClient, analysis, companies);
    }

    private HotspotService create(HotspotRepository.State initial) {
        when(repository.load()).thenReturn(Optional.ofNullable(initial));
        doAnswer(call -> { saved.add(call.getArgument(0)); return null; }).when(repository).save(any());
        var service = new HotspotService(newsClient, analysis, companies, repository, true, 120);
        services.add(service);
        return service;
    }

    private void awaitFinished(HotspotService service) {
        await().atMost(Duration.ofSeconds(3)).until(() -> !"RUNNING".equals(service.current().status()));
    }
}
