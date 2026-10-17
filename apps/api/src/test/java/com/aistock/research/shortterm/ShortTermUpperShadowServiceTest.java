package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ShortTermUpperShadowServiceTest {
    private final List<ShortTermUpperShadowService> scanners = new ArrayList<>();
    private final Instant time = Instant.parse("2026-10-15T06:00:00Z");
    private final TradingClockService clock = new TradingClockService(Clock.fixed(time, ZoneId.of("Asia/Shanghai")));
    private final ShortTermUpperShadowService service = newService(
            mock(EastMoneyClient.class), mock(FirstBoardDailyBarStore.class), clock, mock(ShortTermDailyHistoryService.class));

    @Test
    void observesABoxFollowedByALongUpperShadowWithoutClaimingIntent() {
        var result = service.evaluate(quote(), bars(), dates());
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().upperShadowRatioPercent()).isGreaterThan(new BigDecimal("50"));
        assertThat(result.orElseThrow().label()).isEqualTo("横盘长上影观察");
        assertThat(result.orElseThrow().counterEvidence()).anyMatch(text -> text.contains("无法确认"));
    }

    @Test
    void missingOrDuplicateCompletedDatesCannotDescribeLongSidewaysTrading() {
        List<EastMoneyKLine> incomplete = new ArrayList<>(bars());
        incomplete.set(0, incomplete.get(1));
        assertThat(service.evaluate(quote(), incomplete, dates())).isEmpty();
    }

    @Test
    void anAlreadyWideTradingRangeIsNotAConsolidationBox() {
        List<EastMoneyKLine> wide = new ArrayList<>(bars());
        var row = wide.get(0);
        wide.set(0, new EastMoneyKLine(row.symbol(), row.tradeDate(), bd("10"), bd("10"), bd("13"), bd("9"), bd("100"), bd("1000")));
        assertThat(service.evaluate(quote(), wide, dates())).isEmpty();
    }

    @Test
    void missingIntradayOhlcDoesNotBecomeAConfirmedZeroMatch() {
        var client = mock(EastMoneyClient.class);
        var q = quote();
        var missing = new EastMoneyQuote(q.symbol(), q.name(), q.market(), q.industry(), q.latestPrice(), q.changePercent(),
                q.turnoverRate(), q.volume(), q.amount(), null, null, null, q.sourceName(), "", time, q.tradeDate(), time);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(missing));
        var result = newService(client, mock(FirstBoardDailyBarStore.class), clock, mock(ShortTermDailyHistoryService.class)).scan();
        assertThat(result.status()).isEqualTo("DATA_BLOCKED");
        assertThat(result.dataGaps()).anyMatch(text -> text.contains("未判断形态"));
    }

    @Test
    void repeatedLargeShadowsAreNotClassifiedAsASuddenChange() {
        List<EastMoneyKLine> repeated = bars().stream().map(row -> new EastMoneyKLine(row.symbol(), row.tradeDate(),
                row.open(), row.close(), bd("10.6"), row.low(), row.volume(), row.amount())).toList();
        assertThat(service.evaluate(quote(), repeated, dates())).isEmpty();
    }

    @Test
    void anIncompatiblePriceBasisIsAHistoricalGap() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(quote()));
        List<EastMoneyKLine> incompatible = bars().stream().map(row -> new EastMoneyKLine(row.symbol(), row.tradeDate(),
                bd("9"), bd("9"), bd("9.3"), bd("8.8"), row.volume(), row.amount())).toList();
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(incompatible);
        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.historyMissingCount()).isEqualTo(1);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void fetchingTimeDoesNotTurnUpdatedQuotesIntoFutureData() {
        AtomicReference<Instant> current = new AtomicReference<>(time.minusSeconds(15));
        Clock moving = new Clock() {
            public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenAnswer(invocation -> { current.set(time); return market(quote()); });
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());
        var result = newService(client, store, new TradingClockService(moving), mock(ShortTermDailyHistoryService.class)).scan();
        assertThat(result.validQuoteCount()).isEqualTo(1);
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void repairsOnlyTheIntradayShapePoolBeforeReadingItsConsolidationWindow() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        var history = mock(ShortTermDailyHistoryService.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(quote()));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());
        when(history.ensureWindow(List.of("600001"), LocalDate.of(2026, 10, 15), 20))
                .thenReturn(List.of());

        var result = newService(client, store, clock, history).scan();

        verify(history).ensureWindow(List.of("600001"), LocalDate.of(2026, 10, 15), 20);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.status()).isEqualTo("READY");
    }

    @Test
    void historicalRepairFailuresRemainVisibleInsteadOfLookingLikeZeroMatches() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        var history = mock(ShortTermDailyHistoryService.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(quote()));
        when(history.ensureWindow(List.of("600001"), LocalDate.of(2026, 10, 15), 20))
                .thenReturn(List.of("600001 连续日线补齐失败"));

        var result = newService(client, store, clock, history).scan();

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.historyMissingCount()).isEqualTo(1);
        assertThat(result.dataGaps()).contains("600001 连续日线补齐失败");
    }

    @Test
    void returnsEveryMatchedCandidateInsteadOfTruncatingAfterThirty() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        var history = mock(ShortTermDailyHistoryService.class);
        List<EastMoneyQuote> quotes = IntStream.range(0, 125)
                .mapToObj(index -> quote(String.format("600%03d", index), "形态样本", time)).toList();
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(
                quotes, quotes.size(), quotes.size(), quotes.size(), 0, true, "fixture", time));
        when(store.recentBars(any(), any(LocalDate.class), eq(20))).thenAnswer(invocation -> bars(invocation.getArgument(0)));

        var result = newService(client, store, clock, history).scan();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candidates()).hasSize(125);
        assertThat(result.matchedCount()).isEqualTo(125);
        assertThat(result.omittedCount()).isZero();
        assertThat(result.candidates()).extracting(ShortTermUpperShadowScan.Candidate::symbol)
                .containsExactlyElementsOf(quotes.stream().map(EastMoneyQuote::symbol).toList());
        verify(history).ensureWindow(quotes.stream().map(EastMoneyQuote::symbol).toList(), LocalDate.of(2026, 10, 15), 20);
        verify(client, never()).fetchStockBoardIndustry(any());
    }

    @Test
    void enrichesEveryMissingIndustryWithAtMostFourConcurrentRequestsAndKeepsCandidateOrder() throws Exception {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        List<EastMoneyQuote> quotes = IntStream.range(0, 35)
                .mapToObj(index -> quote(String.format("600%03d", index), "形态样本", time, null, bd("10.1"))).toList();
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(
                quotes, quotes.size(), quotes.size(), quotes.size(), 0, true, "fixture", time));
        when(store.recentBars(any(), any(LocalDate.class), eq(20))).thenAnswer(invocation -> bars(invocation.getArgument(0)));
        CountDownLatch firstWorkers = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.fetchStockBoardIndustry(any())).thenAnswer(invocation -> {
            maximum.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            firstWorkers.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test worker timed out");
                return "行业" + invocation.getArgument(0);
            } finally { inFlight.decrementAndGet(); }
        });
        var scanner = newService(client, store, clock, mock(ShortTermDailyHistoryService.class));
        CompletableFuture<ShortTermUpperShadowScan> run = CompletableFuture.supplyAsync(scanner::scan);
        try { assertThat(firstWorkers.await(2, TimeUnit.SECONDS)).isTrue(); }
        finally { release.countDown(); }
        var result = run.get(5, TimeUnit.SECONDS);

        assertThat(maximum.get()).isEqualTo(4);
        assertThat(result.candidates()).hasSize(35);
        assertThat(result.candidates()).extracting(ShortTermUpperShadowScan.Candidate::symbol)
                .containsExactlyElementsOf(quotes.stream().map(EastMoneyQuote::symbol).toList());
        assertThat(result.candidates()).allSatisfy(candidate -> assertThat(candidate.industry()).isEqualTo("行业" + candidate.symbol()));
        for (var quote : quotes) verify(client).fetchStockBoardIndustry(quote.symbol());
        verify(client, never()).fetchCompanySurveyIndustry(any());
    }

    @Test
    void refreshesStaleQuotesFromTheVerifiedIdentityListBeforeAssessingShapes() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(market(quote("600001", "形态样本", time.minusSeconds(180))));
        when(client.fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE)).thenReturn(List.of(quote()));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.source()).contains("腾讯批量刷新");
        verify(client).fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE);
    }

    @Test
    void acceptsTheSameDayClosingQuoteAt1515AndKeepsThePriorTwentyDayBox() {
        Instant assessedAt = Instant.parse("2026-10-15T07:15:00Z");
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        var history = mock(ShortTermDailyHistoryService.class);
        var closingQuote = timedQuote(Instant.parse("2026-10-15T07:00:00Z"),
                LocalDate.of(2026, 10, 15), assessedAt);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(closingQuote));
        when(store.recentBars("600001", LocalDate.of(2026, 10, 14), 20)).thenReturn(bars());

        var result = newService(client, store, clockAt(assessedAt), history).scan();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.validQuoteCount()).isEqualTo(1);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).marketTimestamp()).isEqualTo(closingQuote.marketTimestamp());
        verify(history).ensureWindow(List.of("600001"), LocalDate.of(2026, 10, 15), 20);
        verify(store).recentBars("600001", LocalDate.of(2026, 10, 14), 20);
        verify(client, never()).fetchTencentQuotes(any(), eq(Integer.MAX_VALUE));
    }

    @Test
    void describesTheClosingShapeWithoutCallingItAnUnfinishedDailyBar() {
        Instant assessedAt = Instant.parse("2026-10-15T07:15:00Z");
        var result = scanAt(timedQuote(assessedAt, LocalDate.of(2026, 10, 15), assessedAt), assessedAt);

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.counterEvidence()).noneMatch(text -> text.contains("未完成日K") || text.contains("收盘形态仍可能变化"));
            assertThat(candidate.counterEvidence()).anyMatch(text -> text.contains("收盘") && text.contains("后续"));
        });
    }

    @Test
    void afterCloseRejectsPreCloseOtherDateMissingAndFutureSourceTimesDespiteFreshFetches() {
        Instant assessedAt = Instant.parse("2026-10-15T07:00:00Z");
        LocalDate date = LocalDate.of(2026, 10, 15);
        List<EastMoneyQuote> invalid = List.of(
                timedQuote(assessedAt.minusSeconds(1), date, assessedAt),
                timedQuote(Instant.parse("2026-10-15T04:00:00Z"), date, assessedAt),
                timedQuote(Instant.parse("2026-10-14T07:00:00Z"), date.minusDays(1), assessedAt),
                timedQuote(assessedAt, date.minusDays(1), assessedAt),
                timedQuote(Instant.parse("2026-10-14T07:00:00Z"), date, assessedAt),
                timedQuote(null, date, assessedAt),
                timedQuote(assessedAt.plusSeconds(6), date, assessedAt),
                timedQuote(assessedAt.plusSeconds(86400), date, assessedAt));

        for (var quote : invalid) {
            var result = scanAt(quote, assessedAt);
            assertThat(result.status()).as("source=%s, tradeDate=%s", quote.marketTimestamp(), quote.tradeDate())
                    .isEqualTo("DATA_BLOCKED");
            assertThat(result.validQuoteCount()).isZero();
            assertThat(result.candidates()).isEmpty();
        }
    }

    @Test
    void acceptsTheCloseBoundaryAndTheExistingFiveSecondClockTolerance() {
        Instant close = Instant.parse("2026-10-15T07:00:00Z");
        for (Instant quoteTime : List.of(close, close.plusSeconds(5))) {
            var result = scanAt(timedQuote(quoteTime, LocalDate.of(2026, 10, 15), close), close);
            assertThat(result.status()).as("source=%s", quoteTime).isEqualTo("READY");
            assertThat(result.candidates()).hasSize(1);
        }
    }

    @Test
    void intradayKeepsThe120SecondAndFiveSecondFutureBoundariesAndUnfinishedBarCaution() {
        for (long offset : new long[]{-120, 5, -121, 6}) {
            var result = scanAt(timedQuote(time.plusSeconds(offset), LocalDate.of(2026, 10, 15), time), time);
            if (offset == -120 || offset == 5) {
                assertThat(result.status()).as("source offset=%s", offset).isEqualTo("READY");
                assertThat(result.candidates()).singleElement().satisfies(candidate ->
                        assertThat(candidate.counterEvidence()).anyMatch(text -> text.contains("未完成日K")));
            } else {
                assertThat(result.status()).as("source offset=%s", offset).isEqualTo("DATA_BLOCKED");
                assertThat(result.validQuoteCount()).isZero();
            }
        }
    }

    @Test
    void doesNotApplyTheClosingQuoteAllowanceOnANonTradingDay() {
        Instant assessedAt = Instant.parse("2026-10-17T07:15:00Z");
        var result = scanAt(timedQuote(Instant.parse("2026-10-17T07:00:00Z"),
                LocalDate.of(2026, 10, 17), assessedAt), assessedAt);

        assertThat(result.status()).isEqualTo("DATA_BLOCKED");
        assertThat(result.validQuoteCount()).isZero();
    }

    @Test
    void excludesOnlyTheDeclaredStaticScopeFromTheFreshnessDenominator() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(
                List.of(quote(), quote("600002", "退市样本", time.minusSeconds(86400))),
                2, 2, 2, 0, true, "fixture", time));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.expectedCount()).isEqualTo(1);
        assertThat(result.validQuoteCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("READY");
        verify(client, never()).fetchTencentQuotes(any(), eq(Integer.MAX_VALUE));
    }

    @Test
    void aRefreshDoesNotHideMissingOrStillStaleQuotesInTheSupportedScope() {
        var client = mock(EastMoneyClient.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(market(quote("600001", "形态样本", time.minusSeconds(180))));
        when(client.fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE)).thenReturn(List.of());

        var result = newService(client, mock(FirstBoardDailyBarStore.class), clock,
                mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.expectedCount()).isEqualTo(1);
        assertThat(result.validQuoteCount()).isZero();
        assertThat(result.status()).isEqualTo("DATA_BLOCKED");
    }

    @Test
    void retainsIndustryFromTheSameOriginalSymbolWithoutReusingItsStalePrice() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        var original = quote("600001", "形态样本", time.minusSeconds(180), "电子", bd("20"));
        var refreshed = quote("600001", "形态样本", time, null, bd("10.1"));
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(original));
        when(client.fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE)).thenReturn(List.of(refreshed));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.industry()).isEqualTo("电子");
            assertThat(candidate).usingRecursiveComparison().ignoringFields("industry")
                    .isEqualTo(service.evaluate(refreshed, bars(), dates()).orElseThrow());
        });
        verify(client, never()).fetchStockBoardIndustry(any());
        verify(client, never()).fetchCompanySurveyIndustry(any());
    }

    @Test
    void reorderedRefreshMatchesIndustryBySymbolInsteadOfListPosition() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(List.of(
                quote("600001", "甲", time.minusSeconds(180), "电子", bd("10.1")),
                quote("600002", "乙", time.minusSeconds(180), "机械", bd("10.1"))),
                2, 2, 2, 0, true, "fixture", time));
        when(client.fetchTencentQuotes(List.of("600001", "600002"), Integer.MAX_VALUE)).thenReturn(List.of(
                quote("600002", "乙", time, null, bd("10.1")),
                quote("600001", "甲", time, null, bd("10.1"))));
        when(store.recentBars(any(), any(LocalDate.class), eq(20))).thenAnswer(invocation -> bars(invocation.getArgument(0)));

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.candidates()).extracting(ShortTermUpperShadowScan.Candidate::symbol).containsExactly("600001", "600002");
        assertThat(result.candidates()).extracting(ShortTermUpperShadowScan.Candidate::industry).containsExactly("电子", "机械");
        verify(client, never()).fetchStockBoardIndustry(any());
    }

    @Test
    void resolvesMissingIndustryOnlyAfterTheCandidateQualifies() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(List.of(
                quote("600001", "候选", time, null, bd("10.1")),
                quote("600002", "未入选", time, null, bd("10.7"))), 2, 2, 2, 0, true, "fixture", time));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());
        when(client.fetchStockBoardIndustry("600001")).thenReturn(" 光伏设备 ");

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.candidates()).singleElement().extracting(ShortTermUpperShadowScan.Candidate::industry)
                .isEqualTo("光伏设备");
        verify(client).fetchStockBoardIndustry("600001");
        verify(client, never()).fetchStockBoardIndustry("600002");
        verify(client, never()).fetchCompanySurveyIndustry(any());
    }

    @Test
    void usesCompanySurveyWhenTheBoardIndustryIsUnavailable() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(market(quote("600001", "候选", time, " ", bd("10.1"))));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());
        when(client.fetchStockBoardIndustry("600001")).thenThrow(new IllegalStateException("供应商不可用"));
        when(client.fetchCompanySurveyIndustry("600001")).thenReturn("文化传媒");

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.candidates()).singleElement().extracting(ShortTermUpperShadowScan.Candidate::industry)
                .isEqualTo("文化传媒");
    }

    @Test
    void unavailableIndustryRemainsUnknownWithoutDiscardingTheCandidate() {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(market(quote("600001", "光伏名字不代表行业", time, null, bd("10.1"))));
        when(store.recentBars(eq("600001"), any(LocalDate.class), eq(20))).thenReturn(bars());
        when(client.fetchStockBoardIndustry("600001")).thenReturn(null);
        when(client.fetchCompanySurveyIndustry("600001")).thenThrow(new IllegalStateException("供应商不可用"));

        var result = newService(client, store, clock, mock(ShortTermDailyHistoryService.class)).scan();

        assertThat(result.candidates()).singleElement().extracting(ShortTermUpperShadowScan.Candidate::industry).isNull();
        assertThat(result.status()).isEqualTo("READY");
        verify(client).fetchCompanySurveyIndustry("600001");
    }

    @AfterEach
    void closePools() { scanners.forEach(ShortTermUpperShadowService::shutdown); }

    private ShortTermUpperShadowService newService(EastMoneyClient client, FirstBoardDailyBarStore store,
                                                   TradingClockService tradingClock, ShortTermDailyHistoryService history) {
        var scanner = new ShortTermUpperShadowService(client, store, tradingClock, history);
        scanners.add(scanner);
        return scanner;
    }

    private AshareQuoteSnapshot market(EastMoneyQuote quote) {
        return new AshareQuoteSnapshot(List.of(quote), 1, 1, 1, 0, true, "fixture", time);
    }

    private TradingClockService clockAt(Instant assessedAt) {
        return new TradingClockService(Clock.fixed(assessedAt, ZoneId.of("Asia/Shanghai")));
    }

    private ShortTermUpperShadowScan scanAt(EastMoneyQuote quote, Instant assessedAt) {
        var client = mock(EastMoneyClient.class);
        var store = mock(FirstBoardDailyBarStore.class);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(market(quote));
        when(client.fetchTencentQuotes(List.of(quote.symbol()), Integer.MAX_VALUE)).thenReturn(List.of(quote));
        when(store.recentBars(eq(quote.symbol()), any(LocalDate.class), eq(20))).thenReturn(bars());
        return newService(client, store, clockAt(assessedAt), mock(ShortTermDailyHistoryService.class)).scan();
    }

    private EastMoneyQuote timedQuote(Instant marketTimestamp, LocalDate tradeDate, Instant fetchedAt) {
        var q = quote();
        return new EastMoneyQuote(q.symbol(), q.name(), q.market(), q.industry(), q.latestPrice(), q.changePercent(),
                q.turnoverRate(), q.volume(), q.amount(), q.peRatio(), q.pbRatio(), q.peTtm(), q.sourceName(), q.quoteUrl(),
                fetchedAt, tradeDate, marketTimestamp, q.totalMarketValue(), q.openPrice(), q.highPrice(), q.lowPrice());
    }

    private EastMoneyQuote quote() {
        return quote("600001", "形态样本", time);
    }
    private EastMoneyQuote quote(String symbol, String name, Instant quoteTime) {
        return quote(symbol, name, quoteTime, "电子", bd("10.1"));
    }
    private EastMoneyQuote quote(String symbol, String name, Instant quoteTime, String industry, BigDecimal latestPrice) {
        return new EastMoneyQuote(symbol, name, "SH", industry, latestPrice, bd("1"), bd("3"),
                bd("1000000"), bd("100000000"), null, null, null, "fixture", "", time,
                LocalDate.of(2026, 10, 15), quoteTime, null, bd("10"), bd("10.7"), bd("9.95"));
    }
    private List<LocalDate> dates() {
        List<LocalDate> result = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 10, 15);
        for (int i = 0; i < 20; i++) { date = clock.previousTradingDay(date); result.add(0, date); }
        return result;
    }
    private List<EastMoneyKLine> bars() {
        return bars("600001");
    }
    private List<EastMoneyKLine> bars(String symbol) {
        return dates().stream().map(date -> new EastMoneyKLine(symbol, date, bd("10"), bd("10"),
                bd("10.3"), bd("9.8"), bd("1000000"), bd("10000000"))).toList();
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
