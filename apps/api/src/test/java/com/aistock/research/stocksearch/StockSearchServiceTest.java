package com.aistock.research.stocksearch;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockSearchServiceTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-11-12T02:00:00Z");
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final SinaStockDirectoryClient directoryClient = mock(SinaStockDirectoryClient.class);
    private final MutableClock clock = new MutableClock(FETCHED_AT);
    private final StockSearchService service = new StockSearchService(client, directoryClient, clock);

    @Test
    void findsEveryNameMatchWithoutCompanyPoolLimitAndSortsBySymbol() {
        List<EastMoneyQuote> quotes = new ArrayList<>(IntStream.range(0, 105)
                .mapToObj(index -> quote(String.valueOf(600104 - index), "东方" + index)).toList());
        quotes.add(quote("000001", "平安银行"));
        when(directoryClient.fetch()).thenReturn(snapshot(quotes, 106, true));
        when(client.fetchTencentQuotes(anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> symbols = invocation.getArgument(0);
            return quotes.stream().filter(quote -> symbols.contains(quote.symbol())).toList();
        });

        StockSearchResult result = service.search("  东方  ", false);

        assertThat(result.keyword()).isEqualTo("东方");
        assertThat(result.matchedCount()).isEqualTo(105);
        assertThat(result.stocks()).hasSize(105);
        assertThat(result.stocks()).extracting(EastMoneyQuote::symbol).isSorted();
        assertThat(result.universeCount()).isEqualTo(106);
        assertThat(result.expectedCount()).isEqualTo(106);
        assertThat(result.complete()).isTrue();
        assertThat(result.source()).contains("测试目录", "腾讯行情");
        assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(result.stocks().get(0).turnoverRate()).isEqualByComparingTo("2.5");
        assertThat(result.stocks().get(0).totalMarketValue()).isEqualByComparingTo("10000000000");
        verify(client, never()).fetchAshareQuoteSnapshot(anyInt());
        verify(directoryClient).fetch();
    }

    @Test
    void matchesCodesAndPreservesPartialCoverage() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(
                quote("600000", "浦发银行"), quote("600001", "东方股份")), 5500, false));

        StockSearchResult result = service.search("60000", false);

        assertThat(result.stocks()).extracting(EastMoneyQuote::symbol).containsExactly("600000", "600001");
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.universeCount()).isEqualTo(2);
        assertThat(result.expectedCount()).isEqualTo(5500);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void retainsSuspendedStocksWhenTheQuoteHasNoLatestPrice() {
        var suspended = new EastMoneyQuote("600001", "东方股份", "上交所", null,
                null, null, null, null, null, null, null, null, "测试行情", null, FETCHED_AT);
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(suspended), 1, true));

        StockSearchResult result = service.search("东方", false);
        assertThat(result.stocks()).extracting(EastMoneyQuote::symbol).containsExactly("600001");
        assertThat(result.stocks().get(0).name()).isEqualTo("东方股份");
        assertThat(result.stocks().get(0).latestPrice()).isNull();
        assertThat(result.complete()).isTrue();
        assertThat(result.source()).contains("报价缺失 1");
    }

    @Test
    void rejectsEmptyKeywordsBeforeFetching() {
        for (String keyword : new String[]{null, "", " \t\n ", "\u3000"}) {
            assertThatThrownBy(() -> service.search(keyword, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("关键词");
        }
        verifyNoInteractions(client, directoryClient);
    }

    @Test
    void sharesDirectoryAcrossKeywordsForSixHoursAndOnlyRequestsMatchingQuotes() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(
                quote("600001", "东方股份"), quote("000001", "平安银行")), 2, true));

        assertThat(service.search("东方", false).matchedCount()).isEqualTo(1);
        clock.advanceSeconds(6 * 3600 - 1);
        assertThat(service.search("平安", false).stocks()).extracting(EastMoneyQuote::symbol).containsExactly("000001");
        assertThat(service.search("不存在", false).matchedCount()).isZero();
        verify(directoryClient).fetch();
        verify(client).fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE);
        verify(client).fetchTencentQuotes(List.of("000001"), Integer.MAX_VALUE);

        clock.advanceSeconds(1);
        service.search("东方", false);
        verify(directoryClient, times(2)).fetch();
    }

    @Test
    void startsCacheLifetimeAfterTheFetchCompletes() {
        when(directoryClient.fetch()).thenAnswer(invocation -> {
            clock.advanceSeconds(45);
            return snapshot(List.of(quote("600001", "东方股份")), 1, true);
        });

        service.search("东方", false);
        clock.advanceSeconds(6 * 3600 - 1);
        service.search("东方", false);

        verify(directoryClient).fetch();
    }

    @Test
    void explicitRefreshReplacesTheCachedSnapshot() {
        when(directoryClient.fetch()).thenReturn(
                snapshot(List.of(quote("600001", "东方股份")), 1, true),
                snapshot(List.of(quote("600001", "东方股份"), quote("600002", "东方集团")), 2, true));

        assertThat(service.search("东方", false).matchedCount()).isEqualTo(1);
        assertThat(service.search("东方", true).matchedCount()).isEqualTo(2);
        assertThat(service.search("东方", false).matchedCount()).isEqualTo(2);
        verify(directoryClient, times(2)).fetch();
    }

    @Test
    void failedExpiredRefreshReportsFailureInsteadOfReturningStaleResults() {
        when(directoryClient.fetch()).thenReturn(
                snapshot(List.of(quote("600001", "东方股份")), 1, true))
                .thenThrow(new IllegalStateException("行情源不可用"));
        service.search("东方", false);
        clock.advanceSeconds(6 * 3600);

        assertThatThrownBy(() -> service.search("东方", false))
                .isInstanceOf(IllegalStateException.class).hasMessage("行情源不可用");
    }

    @Test
    void expiresTheDirectoryWhenTheChineseDateChanges() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(quote("600001", "东方股份")), 1, true));
        clock.now = Instant.parse("2026-11-12T15:59:30Z");
        service.search("东方", false);
        clock.advanceSeconds(31);
        service.search("东方", false);
        verify(directoryClient, times(2)).fetch();
    }

    @Test
    void doesNotKeepAnIncompleteDirectoryForSixHours() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(quote("600001", "东方股份")), 5500, false));
        service.search("东方", false);
        clock.advanceSeconds(60);
        service.search("东方", false);
        verify(directoryClient, times(2)).fetch();
    }

    @Test
    void retainsDirectoryIdentityWhenQuotesFailOrReturnADifferentName() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(quote("600001", "东方股份")), 1, true));
        when(client.fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE))
                .thenReturn(List.of(quote("600001", "更名后的名字")))
                .thenThrow(new IllegalStateException("报价失败"));
        StockSearchResult first = service.search("东方", false);
        assertThat(first.stocks().get(0).name()).isEqualTo("东方股份");
        assertThat(first.stocks().get(0).latestPrice()).isEqualByComparingTo("10");
        StockSearchResult second = service.search("东方", false);
        assertThat(second.stocks().get(0).name()).isEqualTo("东方股份");
        assertThat(second.stocks().get(0).latestPrice()).isNull();
        assertThat(second.source()).contains("报价暂不可用");
    }

    @Test
    void fetchesAtMostSixtySymbolsPerBatchWithAtMostSixParallelBatchesAndRetainsFailedBatchIdentities() {
        List<EastMoneyQuote> quotes = IntStream.range(0, 420)
                .mapToObj(index -> quote(String.valueOf(600000 + index), "东方" + index)).toList();
        when(directoryClient.fetch()).thenReturn(snapshot(quotes, 420, true));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        when(client.fetchTencentQuotes(anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> symbols = invocation.getArgument(0);
            assertThat(symbols).hasSizeLessThanOrEqualTo(60);
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            batches.incrementAndGet();
            try {
                Thread.sleep(40);
                if (symbols.contains("600000")) throw new IllegalStateException("首批行情不可用");
                return quotes.stream().filter(quote -> symbols.contains(quote.symbol())).toList();
            } finally {
                active.decrementAndGet();
            }
        });
        var filters = new StockSearchFilters(true, false, false, false,
                null, null, null, null, null, null, null, null, null, null, null, null);

        StockSearchResult result = service.search("", false, filters);

        assertThat(result.candidateCount()).isEqualTo(420);
        assertThat(result.matchedCount()).isEqualTo(420);
        assertThat(result.unverifiedCount()).isZero();
        assertThat(result.stocks().get(0).symbol()).isEqualTo("600000");
        assertThat(result.stocks().get(0).latestPrice()).isNull();
        assertThat(result.stocks().get(419).latestPrice()).isEqualByComparingTo("10");
        assertThat(batches.get()).isEqualTo(7);
        assertThat(maximum.get()).isEqualTo(6);
        verify(client, never()).fetchAshareQuoteSnapshot(anyInt());
    }

    @Test
    void propagatesCancelledQuoteRequestsInsteadOfReturningMissingQuotes() {
        when(directoryClient.fetch()).thenReturn(snapshot(List.of(quote("600001", "东方股份")), 1, true));
        when(client.fetchTencentQuotes(anyList(), anyInt())).thenThrow(new CancellationException("行情请求已取消"));

        assertThatThrownBy(() -> service.search("东方", false))
                .isInstanceOf(CancellationException.class).hasMessage("行情请求已取消");
    }

    @Test
    void homophonesAndPinyinUseTheSameBoardAndNumericFiltersBeforeReturningResults() {
        List<EastMoneyQuote> quotes = List.of(quote("600001", "太平洋"), quote("600002", "阳光股份"),
                quote("300001", "扬子股份"), quote("600003", "祥云股份"));
        when(directoryClient.fetch()).thenReturn(snapshot(quotes, 4, true));
        when(client.fetchTencentQuotes(List.of("600001", "600002"), Integer.MAX_VALUE)).thenReturn(quotes.subList(0, 2));
        var filters = new StockSearchFilters(true, false, false, false,
                new BigDecimal("1"), new BigDecimal("2"), null, null, null, null, null, null, null, null, null, null);

        for (String keyword : List.of("羊", "洋", "阳", "yang", "YÁNG")) {
            StockSearchResult result = service.search(keyword, false, filters);
            assertThat(result.stocks()).extracting(EastMoneyQuote::symbol).containsExactly("600001", "600002");
            assertThat(result.candidateCount()).isEqualTo(2);
            assertThat(result.matchedCount()).isEqualTo(2);
            assertThat(result.unverifiedCount()).isZero();
        }
        verify(directoryClient).fetch();
        verify(client, times(5)).fetchTencentQuotes(List.of("600001", "600002"), Integer.MAX_VALUE);
    }

    private static StockDirectorySnapshot snapshot(List<EastMoneyQuote> quotes, int expected, boolean complete) {
        return new StockDirectorySnapshot(quotes.stream().map(quote -> new StockDirectorySnapshot.Stock(
                quote.symbol(), quote.name(), quote.market())).toList(), expected, complete, "测试目录", FETCHED_AT);
    }

    private static EastMoneyQuote quote(String symbol, String name) {
        return new EastMoneyQuote(symbol, name, "上交所", null, new BigDecimal("10"),
                new BigDecimal("1.5"), new BigDecimal("2.5"), new BigDecimal("100"),
                new BigDecimal("100000"), null, null, null, "测试行情", null, FETCHED_AT,
                null, null, new BigDecimal("10000000000"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
