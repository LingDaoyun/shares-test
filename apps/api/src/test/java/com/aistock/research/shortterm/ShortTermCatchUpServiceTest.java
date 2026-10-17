package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.EastMoneyRelatedBoard;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShortTermCatchUpServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-15");
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final ShortTermLimitUpBoardService boards = mock(ShortTermLimitUpBoardService.class);
    private final FirstBoardDailyBarStore bars = mock(FirstBoardDailyBarStore.class);
    private final TradingClockService clock = new TradingClockService(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ShortTermDailyHistoryService historyPreparation = mock(ShortTermDailyHistoryService.class);
    private final ShortTermCatchUpService service = new ShortTermCatchUpService(boards, client, bars, clock, historyPreparation);

    @Test
    void returnsSupplierBoardChoicesWithoutClaimingCatalystOrFetchingMembers() {
        seedLeader();
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", null);
        assertThat(result.status()).isEqualTo("SELECT_BOARD");
        assertThat(result.relatedBoards()).extracting(EastMoneyRelatedBoard::code).containsExactly("BK0001");
        assertThat(result.evidence()).anyMatch(value -> value.contains("供应商归属"));
        assertThat(result.contraryEvidence()).anyMatch(value -> value.contains("不证明"));
        verify(client, never()).fetchBoardQuoteSnapshot(anyString());
        verifyNoInteractions(historyPreparation);
    }

    @Test
    void rejectsAnAnchorOutsideSelectedIndustryAndUnrelatedBoard() {
        seedLeader();
        assertThatThrownBy(() -> service.snapshot("医药", DATE, "600001", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("行业");
        assertThatThrownBy(() -> service.snapshot("半导体", DATE, "600001", "BK9999"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("所属板块");
    }

    @Test
    void historicalDateCannotReuseCurrentSupplierMembership() {
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE.minusDays(1), "600001", "BK0001");
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("历史归属"));
        verifyNoInteractions(client, boards, bars);
    }

    @Test
    void usesSixExactPriorCompletedClosesAndRanksAgainstAllMembersIncludingAnchor() {
        seedUniverse(6);
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.historyDates()).containsExactly(LocalDate.parse("2026-09-30"),
                LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-12"),
                LocalDate.parse("2026-10-13"), LocalDate.parse("2026-10-14"));
        assertThat(result.historyCoveragePercent()).isEqualByComparingTo("100.00");
        assertThat(result.historyValidCount()).isEqualTo(6);
        assertThat(result.candidates()).filteredOn(ShortTermCatchUpCandidate::middle)
                .extracting(ShortTermCatchUpCandidate::symbol).containsExactly("600003", "600004");
        ShortTermCatchUpCandidate candidate = result.candidates().stream()
                .filter(row -> row.symbol().equals("600003")).findFirst().orElseThrow();
        assertThat(candidate.return5()).isEqualByComparingTo("20.0000");
        assertThat(candidate.returnPercentile()).isEqualByComparingTo("40.00");
        assertThat(candidate.returnGapToLeader()).isEqualByComparingTo("20.0000");
        verify(bars, never()).closingPrices(anyCollection(), eq(DATE));
        assertThat(result.nextTradingDate()).isEqualTo(DATE.plusDays(1));
    }

    @Test
    void missingIntermediateCloseKeepsQuoteCandidateButHistoryUnknown() {
        seedUniverse(6);
        when(bars.closingPrices(anyCollection(), eq(LocalDate.parse("2026-10-12"))))
                .thenReturn(Map.of("600001", BigDecimal.TEN, "600002", BigDecimal.TEN,
                        "600004", BigDecimal.TEN, "600005", BigDecimal.TEN, "600006", BigDecimal.TEN));
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.historyValidCount()).isEqualTo(5);
        ShortTermCatchUpCandidate candidate = result.candidates().stream()
                .filter(row -> row.symbol().equals("600003")).findFirst().orElseThrow();
        assertThat(candidate.return5()).isNull();
        assertThat(candidate.returnPercentile()).isNull();
        assertThat(candidate.middle()).isFalse();
        assertThat(candidate.dataGaps()).anyMatch(value -> value.contains("连续六个"));
    }

    @Test
    void fewerThanFiveSamplesOrBelowEightyPercentCoverageCannotAssignMiddle() {
        seedUniverse(6);
        when(bars.closingPrices(anyCollection(), eq(LocalDate.parse("2026-10-14"))))
                .thenReturn(Map.of("600001", BigDecimal.TEN, "600002", BigDecimal.TEN,
                        "600003", BigDecimal.TEN, "600004", BigDecimal.TEN));
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.historyCoveragePercent()).isEqualByComparingTo("66.67");
        assertThat(result.candidates()).allSatisfy(row -> {
            assertThat(row.middle()).isFalse();
            assertThat(row.returnPercentile()).isNull();
        });
    }

    @Test
    void exactlyFiveValidSamplesPermitInclusiveQuartileBoundariesButFourAtEightyPercentDoNot() {
        seedUniverse(5);
        ShortTermCatchUpSnapshot full = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(full.candidates()).filteredOn(ShortTermCatchUpCandidate::middle)
                .extracting(row -> row.returnPercentile().stripTrailingZeros().toPlainString())
                .containsExactly("50", "25", "75");
        when(bars.closingPrices(anyCollection(), eq(LocalDate.parse("2026-10-14"))))
                .thenReturn(Map.of("600001", BigDecimal.TEN, "600002", BigDecimal.TEN,
                        "600003", BigDecimal.TEN, "600004", BigDecimal.TEN));
        ShortTermCatchUpSnapshot four = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(four.historyCoveragePercent()).isEqualByComparingTo("80.00");
        assertThat(four.candidates()).noneMatch(ShortTermCatchUpCandidate::middle);
    }

    @Test
    void tiedPriorReturnsUseTheSameMiddleRankForEveryConstituent() {
        seedUniverse(6);
        when(bars.closingPrices(anyCollection(), eq(LocalDate.parse("2026-10-14"))))
                .thenReturn(Map.of("600001", BigDecimal.TEN, "600002", BigDecimal.TEN,
                        "600003", BigDecimal.TEN, "600004", BigDecimal.TEN,
                        "600005", BigDecimal.TEN, "600006", BigDecimal.TEN));
        assertThat(service.snapshot("半导体", DATE, "600001", "BK0001").candidates()).allSatisfy(row -> {
            assertThat(row.returnPercentile()).isEqualByComparingTo("50.00");
            assertThat(row.middle()).isTrue();
        });
    }

    @Test
    void localHistoryFailurePreservesBoardQuoteFactsWithUnknownHistory() {
        seedUniverse(6);
        when(bars.closingPrices(anyCollection(), eq(LocalDate.parse("2026-10-13"))))
                .thenThrow(new IllegalStateException("本地日线不可用"));
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.risingCount()).isEqualTo(6);
        assertThat(result.sealedCount()).isEqualTo(1);
        assertThat(result.candidates()).hasSize(5).allSatisfy(row -> assertThat(row.return5()).isNull());
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("本地日线"));
    }

    @Test
    void preparesOnlyVerifiedFullBoardMembersBeforeReadingTheSixDayCrossSection() {
        seedLeader();
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(quotes(6)));
        when(historyPreparation.ensureWindow(anyCollection(), eq(DATE), eq(20))).thenAnswer(invocation -> {
            LocalDate day = DATE;
            for (int index = 0; index < 6; index++) {
                day = clock.previousTradingDay(day);
                when(bars.closingPrices(anyCollection(), eq(day))).thenReturn(Map.of(
                        "600001", BigDecimal.TEN, "600002", BigDecimal.TEN, "600003", BigDecimal.TEN,
                        "600004", BigDecimal.TEN, "600005", BigDecimal.TEN, "600006", BigDecimal.TEN));
            }
            return List.of();
        });
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.historyValidCount()).isEqualTo(6);
        assertThat(result.candidates()).allMatch(ShortTermCatchUpCandidate::middle);
        verify(historyPreparation).ensureWindow(argThat(symbols -> symbols.size() == 6
                && symbols.containsAll(List.of("600001", "600002", "600003", "600004", "600005", "600006"))), eq(DATE), eq(20));
    }

    @Test
    void repairWaitCannotTurnExpiredQuotesIntoFreshObservations() {
        seedUniverse(6);
        TradingClockService advancing = spy(clock);
        when(advancing.currentMarketDateTime()).thenReturn(LocalDateTime.parse("2026-10-15T10:00:00"),
                LocalDateTime.parse("2026-10-15T10:00:00"), LocalDateTime.parse("2026-10-15T10:00:00"),
                LocalDateTime.parse("2026-10-15T10:02:01"));
        ShortTermCatchUpSnapshot result = new ShortTermCatchUpService(boards, client, bars, advancing, historyPreparation)
                .snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.status()).isEqualTo("DATA_GAP");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("请刷新"));
        verifyNoInteractions(bars);
    }

    @Test
    void incompleteIdentityMixedDateOrStaleRowInvalidatesWholeBoardComparison() {
        seedUniverse(6);
        List<EastMoneyQuote> invalid = new ArrayList<>(quotes(6));
        invalid.set(5, quote("600006", "样本6", DATE.minusDays(1), NOW.minusSeconds(600), "1", "100"));
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(invalid));
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.status()).isEqualTo("DATA_GAP");
        assertThat(result.fetchedMemberCount()).isEqualTo(6);
        assertThat(result.validQuoteCount()).isEqualTo(5);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.risingCount()).isNull();
        assertThat(result.sealedCount()).isNull();
        verifyNoInteractions(bars);
        verifyNoInteractions(historyPreparation);
    }

    @Test
    void sourceTimestampAdvancingDuringFetchIsComparedWithPostFetchClock() {
        seedUniverse(6);
        TradingClockService advancing = spy(clock);
        when(advancing.currentMarketDateTime()).thenReturn(
                LocalDateTime.parse("2026-10-15T10:00:00"), LocalDateTime.parse("2026-10-15T10:00:05"));
        List<EastMoneyQuote> advancedQuotes = IntStream.rangeClosed(1, 6).mapToObj(index -> quote(
                String.format("600%03d", index), "样本" + index, DATE, NOW.plusSeconds(3), "100", "1")).toList();
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(advancedQuotes));
        ShortTermCatchUpSnapshot result = new ShortTermCatchUpService(boards, client, bars, advancing, historyPreparation)
                .snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.marketTimestamp()).isEqualTo(NOW.plusSeconds(3));
        assertThat(result.fetchedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void absentAnchorDuplicateIdentityAndIncompleteMembershipDoNotBecomeCompleteUniverse() {
        seedUniverse(6);
        List<EastMoneyQuote> duplicate = new ArrayList<>(quotes(6));
        duplicate.set(5, duplicate.get(4));
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(duplicate));
        assertThat(service.snapshot("半导体", DATE, "600001", "BK0001").candidates()).isEmpty();
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(quotes(6).subList(1, 6)));
        assertThat(service.snapshot("半导体", DATE, "600001", "BK0001").status()).isEqualTo("DATA_GAP");
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(new AshareQuoteSnapshot(
                quotes(6), 8, 8, 6, 2, false, "fixture", NOW));
        ShortTermCatchUpSnapshot incomplete = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(incomplete.expectedMemberCount()).isEqualTo(8);
        assertThat(incomplete.candidates()).isEmpty();
    }

    @Test
    void filtersStAndDelistingAndCapsDisplayedScopeAtTwenty() {
        seedUniverse(25);
        List<EastMoneyQuote> members = new ArrayList<>(quotes(25));
        members.set(23, quote("600024", "*ST样本", DATE, NOW, "1", "100"));
        members.set(24, quote("600025", "退市样本", DATE, NOW, "1", "100"));
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(members));
        ShortTermCatchUpSnapshot result = service.snapshot("半导体", DATE, "600001", "BK0001");
        assertThat(result.expectedMemberCount()).isEqualTo(25);
        assertThat(result.eligibleCandidateCount()).isEqualTo(22);
        assertThat(result.displayedCount()).isEqualTo(20);
        assertThat(result.truncatedCount()).isEqualTo(2);
        assertThat(result.candidates()).noneMatch(row -> row.name().contains("ST") || row.name().contains("退"));
    }

    @Test
    void followUpWaitsUntilExactNextVerifiedTradingDayAndExpiresAfterIt() {
        ShortTermCatchUpFollowUpRequest request = request(DATE, "10");
        assertThat(service.followUp(request).results().get(0).status()).isEqualTo("WAITING");
        ShortTermCatchUpService later = at(Instant.parse("2026-10-19T02:00:00Z"));
        assertThat(later.followUp(request).results().get(0).status()).isEqualTo("EXPIRED");
        verifyNoInteractions(client);
    }

    @Test
    void followUpUsesFreshNextDayActualQuoteAndUnadjustedObservedPriceChange() {
        ShortTermCatchUpService nextDay = at(Instant.parse("2026-10-16T02:00:00Z"));
        when(client.fetchTencentQuotes(List.of("600002"), 50)).thenReturn(List.of(
                new EastMoneyQuote("600002", "候选", "SH", "半导体", new BigDecimal("11"), BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, null, null, null, "fixture", null, NOW,
                        DATE.plusDays(1), Instant.parse("2026-10-16T02:00:00Z"), null,
                        BigDecimal.TEN, new BigDecimal("99"), BigDecimal.TEN)));
        ShortTermCatchUpFollowUpResult row = nextDay.followUp(request(DATE, "10")).results().get(0);
        assertThat(row.status()).isEqualTo("OBSERVED");
        assertThat(row.priceChangePercent()).isEqualByComparingTo("10.0000");
        assertThat(row.explanation()).contains("未调整价格变动").contains("非实际成交收益").contains("除权");
        verifyNoInteractions(boards, bars);
    }

    @Test
    void verifiedHolidayCalendarSkipsTheWholeNationalDayBreakForExactFollowUp() {
        ShortTermCatchUpFollowUpRequest beforeHoliday = request(LocalDate.parse("2026-09-30"), "10");
        ShortTermCatchUpFollowUpResult waiting = at(Instant.parse("2026-10-02T02:00:00Z"))
                .followUp(beforeHoliday).results().get(0);
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.followUpDate()).isEqualTo(LocalDate.parse("2026-10-08"));
        verifyNoInteractions(client);
        ShortTermCatchUpFollowUpResult invalid = service.followUp(request(LocalDate.parse("2026-10-01"), "10"))
                .results().get(0);
        assertThat(invalid.status()).isEqualTo("UNKNOWN");
        assertThat(invalid.followUpDate()).isNull();
    }

    @Test
    void followUpTreatsNoVolumeStaleQuotesAndUnverifiedCalendarAsUnknown() {
        Instant nextTime = Instant.parse("2026-10-16T02:00:00Z");
        ShortTermCatchUpService nextDay = at(nextTime);
        when(client.fetchTencentQuotes(List.of("600002"), 50)).thenReturn(List.of(
                quote("600002", "候选", DATE.plusDays(1), nextTime, "11", "0")));
        assertThat(nextDay.followUp(request(DATE, "10")).results().get(0).status()).isEqualTo("UNKNOWN");
        when(client.fetchTencentQuotes(List.of("600002"), 50)).thenReturn(List.of(
                quote("600002", "候选", DATE.plusDays(1), nextTime.minusSeconds(121), "11", "1")));
        assertThat(nextDay.followUp(request(DATE, "10")).results().get(0).priceChangePercent()).isNull();
        assertThat(service.followUp(request(LocalDate.parse("2026-12-31"), "10")).results().get(0).status())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void followUpRejectsOversizedBatchAndDoesNotUseOffSessionPrices() {
        List<ShortTermCatchUpObservation> observations = IntStream.range(0, 51)
                .mapToObj(index -> new ShortTermCatchUpObservation("o" + index, DATE, "600002", "600001", BigDecimal.TEN))
                .toList();
        assertThatThrownBy(() -> service.followUp(new ShortTermCatchUpFollowUpRequest(observations)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50");
        ShortTermCatchUpFollowUpResult row = at(Instant.parse("2026-10-16T08:00:00Z"))
                .followUp(request(DATE, "10")).results().get(0);
        assertThat(row.status()).isEqualTo("UNKNOWN");
        verifyNoInteractions(client);
    }

    private void seedLeader() {
        ShortTermLimitUpStock leader = new ShortTermLimitUpStock("600001", "锚点", "半导体", BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 2, 2, 2, BigDecimal.TEN,
                LocalTime.of(9, 30), LocalTime.of(9, 30), 0);
        when(boards.snapshot(DATE)).thenReturn(new ShortTermLimitUpBoardSnapshot(DATE, NOW, true, null,
                List.of(leader), true, List.of(), List.of(), null, null, List.of()));
        when(client.fetchStockRelatedBoards("600001")).thenReturn(List.of(new EastMoneyRelatedBoard("BK0001", "供应商板块")));
    }

    private void seedUniverse(int size) {
        seedLeader();
        when(client.fetchBoardQuoteSnapshot("BK0001")).thenReturn(quoteSnapshot(quotes(size)));
        LocalDate cursor = DATE;
        for (int index = 0; index < 6; index++) {
            cursor = clock.previousTradingDay(cursor);
            Map<String, BigDecimal> closes = new LinkedHashMap<>();
            for (int stock = 1; stock <= size; stock++) {
                closes.put(String.format("600%03d", stock), index == 0
                        ? BigDecimal.TEN.add(BigDecimal.valueOf(stock - 1)) : BigDecimal.TEN);
            }
            when(bars.closingPrices(anyCollection(), eq(cursor))).thenReturn(closes);
        }
    }

    private List<EastMoneyQuote> quotes(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(index -> quote(String.format("600%03d", index),
                "样本" + index, DATE, NOW, "100", "1")).toList();
    }

    private AshareQuoteSnapshot quoteSnapshot(List<EastMoneyQuote> quotes) {
        return new AshareQuoteSnapshot(quotes, quotes.size(), quotes.size(), quotes.size(), 0, true, "fixture", NOW);
    }

    private EastMoneyQuote quote(String symbol, String name, LocalDate date, Instant timestamp, String price, String volume) {
        return new EastMoneyQuote(symbol, name, "SH", "半导体", new BigDecimal(price), BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal(volume), BigDecimal.TEN, null, null, null,
                "fixture", null, NOW, date, timestamp);
    }

    private ShortTermCatchUpService at(Instant instant) {
        return new ShortTermCatchUpService(boards, client, bars,
                new TradingClockService(Clock.fixed(instant, ZoneOffset.UTC)), historyPreparation);
    }

    private ShortTermCatchUpFollowUpRequest request(LocalDate date, String price) {
        return new ShortTermCatchUpFollowUpRequest(List.of(new ShortTermCatchUpObservation(
                "first", date, "600002", "600001", new BigDecimal(price))));
    }
}
