package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyConceptClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MarketThemeServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-10-26");
    private final EastMoneyConceptClient source = mock(EastMoneyConceptClient.class);
    private final ShortTermLimitUpBoardService limitUps = mock(ShortTermLimitUpBoardService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final MarketThemeService service = new MarketThemeService(source, limitUps, clock);

    @Test
    void filtersStructuralTagsAndRanksExplicitThemesWithoutMemberFanout() {
        boards(board("BK0596", "融资融券", "9", NOW), board("BK1090", "机器人概念", "3", NOW),
                board("BK1184", "人形机器人", "4", NOW), board("BK1145", "机器人执行器", "2", NOW),
                board("BK0705", "结构标签1", "9", NOW), board("BK0638", "结构标签2", "9", NOW),
                board("BK0743", "结构标签3", "9", NOW), board("BK0867", "结构标签4", "9", NOW),
                board("BK1050", "结构标签5", "9", NOW), board("BK1051", "结构标签6", "9", NOW),
                board("BK1632", "结构标签7", "9", NOW), board("BK1633", "结构标签8", "9", NOW),
                board("BK1645", "结构标签9", "9", NOW));

        MarketThemeSnapshot snapshot = service.snapshot();

        assertThat(snapshot.status()).isEqualTo("READY");
        assertThat(snapshot.totalConceptCount()).isEqualTo(13);
        assertThat(snapshot.excludedStructuralCount()).isEqualTo(10);
        assertThat(snapshot.validQuoteCount()).isEqualTo(3);
        assertThat(snapshot.themes()).extracting(MarketTheme::code).containsExactly("BK1184", "BK1090", "BK1145");
        assertThat(snapshot.themes().get(0).advancingRatioPercent()).isEqualByComparingTo("60");
        verify(source).fetchBoards();
        verifyNoMoreInteractions(source);
        verifyNoInteractions(limitUps);
    }

    @Test
    void neverBorrowsMissingFutureOrDifferentDayQuotesAndPreservesRealZero() {
        boards(board("BK1090", "机器人概念", "0", NOW), board("BK1184", "人形机器人", "9", NOW.minusSeconds(86400)),
                board("BK1145", "机器人执行器", "8", NOW.plusSeconds(1)), board("BK1000", "测试题材", "7", null));
        MarketThemeSnapshot snapshot = service.snapshot();
        assertThat(snapshot.tradeDate()).isEqualTo(DATE);
        assertThat(snapshot.validQuoteCount()).isEqualTo(1);
        assertThat(snapshot.status()).isEqualTo("PARTIAL");
        assertThat(snapshot.themes().get(0).changePercent()).isEqualByComparingTo("0");
        assertThat(snapshot.themes().subList(1, 4)).allSatisfy(theme -> {
            assertThat(theme.changePercent()).isNull();
            assertThat(theme.amount()).isNull();
            assertThat(theme.advancingRatioPercent()).isNull();
        });
        assertThat(snapshot.themes().stream().filter(theme -> theme.code().equals("BK1145")).findFirst().orElseThrow().marketTimestamp()).isNull();
        assertThat(snapshot.themes().stream().filter(theme -> theme.code().equals("BK1184")).findFirst().orElseThrow().marketTimestamp())
                .isEqualTo(NOW.minusSeconds(86400));
    }

    @Test
    void incompleteBreadthRemainsUnknownAndOldSourceDateIsDisclosed() {
        Instant old = NOW.minusSeconds(86400 * 3);
        boards(new EastMoneyConceptClient.Board("BK1090", "机器人概念", BigDecimal.ZERO, BigDecimal.ZERO,
                0, null, 0, old));
        MarketThemeSnapshot snapshot = service.snapshot();
        assertThat(snapshot.tradeDate()).isEqualTo(DATE.minusDays(3));
        assertThat(snapshot.fetchedAt()).isEqualTo(NOW);
        assertThat(snapshot.themes().get(0).advancingRatioPercent()).isNull();
        assertThat(snapshot.dataGaps()).anyMatch(gap -> gap.contains("2026-10-23"));
        assertThat(snapshot.status()).isEqualTo("PARTIAL");
    }

    @Test
    void successAndFailureHaveBoundedCacheAndStaleKeepsOriginalTime() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        MarketThemeSnapshot first = service.snapshot();
        clock.now = NOW.plusSeconds(59);
        assertThat(service.snapshot()).isSameAs(first);
        when(source.fetchBoards()).thenThrow(new IllegalStateException("source unavailable"));
        clock.now = NOW.plusSeconds(60);
        MarketThemeSnapshot stale = service.snapshot();
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.fetchedAt()).isEqualTo(first.fetchedAt());
        assertThat(stale.themes()).isEqualTo(first.themes());
        clock.now = NOW.plusSeconds(69);
        assertThat(service.snapshot()).isSameAs(stale);
        verify(source, times(2)).fetchBoards();
        clock.now = NOW.plusSeconds(70);
        service.snapshot();
        verify(source, times(3)).fetchBoards();
    }

    @Test
    void failureWithoutSnapshotAndMissingSourceTimesCannotClaimAvailability() {
        when(source.fetchBoards()).thenThrow(new IllegalStateException("unavailable"));
        MarketThemeSnapshot failed = service.snapshot();
        assertThat(failed.status()).isEqualTo("UNAVAILABLE");
        assertThat(failed.tradeDate()).isNull();
        clock.now = NOW.plusSeconds(10);
        boards(board("BK1090", "机器人概念", "3", null));
        MarketThemeSnapshot undated = service.snapshot();
        assertThat(undated.tradeDate()).isNull();
        assertThat(undated.validQuoteCount()).isZero();
        assertThat(undated.themes().get(0).changePercent()).isNull();
    }

    @Test
    void detailIntersectsCompleteMembershipWithSameDateLimitUpsAndDeduplicates() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 2,
                List.of(member("600001", NOW), member("600002", NOW.minusSeconds(86400)))));
        when(limitUps.cachedSnapshot(DATE)).thenReturn(pool(DATE, List.of(stock("600001", 3), stock("600001", 3), stock("600099", 5))));

        MarketThemeDetail detail = service.detail("BK1090", DATE);

        assertThat(detail.membershipComplete()).isTrue();
        assertThat(detail.memberCount()).isEqualTo(2);
        assertThat(detail.limitUpCount()).isEqualTo(1);
        assertThat(detail.maxConsecutiveBoards()).isEqualTo(3);
        assertThat(detail.limitUpFetchedAt()).isEqualTo(NOW);
        assertThat(detail.limitUpStocks()).extracting(ShortTermLimitUpStock::symbol).containsExactly("600001");
        assertThat(detail.members().get(1).latestPrice()).isNull();
        assertThat(detail.dataGaps()).anyMatch(gap -> gap.contains("成分行情"));
        verify(source).fetchMembers("BK1090");
        verify(limitUps, never()).snapshot(any());
    }

    @Test
    void knownEmptyIntersectionIsZeroButUnavailableOrWrongDatePoolIsUnknown() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 1, List.of(member("600001", NOW))));
        when(limitUps.cachedSnapshot(DATE)).thenReturn(pool(DATE, List.of()));
        assertThat(service.detail("BK1090", DATE).limitUpCount()).isZero();
        assertThat(service.detail("BK1090", DATE).maxConsecutiveBoards()).isZero();
        when(limitUps.cachedSnapshot(DATE)).thenReturn(ShortTermLimitUpBoardSnapshot.unavailable(DATE, NOW, "缺失"));
        assertThat(service.detail("BK1090", DATE).limitUpCount()).isNull();
        when(limitUps.cachedSnapshot(DATE)).thenReturn(pool(DATE.minusDays(1), List.of(stock("600001", 3))));
        assertThat(service.detail("BK1090", DATE).limitUpCount()).isNull();
        verify(limitUps, never()).snapshot(any());
    }

    @Test
    void missingCachedPoolDoesNotTriggerNetworkRefreshOrPublishStatisticsTime() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 1, List.of(member("600001", NOW))));

        MarketThemeDetail detail = service.detail("BK1090", DATE);

        assertThat(detail.limitUpCount()).isNull();
        assertThat(detail.limitUpFetchedAt()).isNull();
        assertThat(detail.dataGaps()).anyMatch(gap -> gap.contains("同日涨停池未就绪或已过期") && gap.contains("刷新涨停看板"));
        verify(limitUps).cachedSnapshot(DATE);
        verify(limitUps, never()).snapshot(any());
        verifyNoMoreInteractions(limitUps);
    }

    @Test
    void incompleteMembershipDoesNotBecomeZeroAndDoesNotFetchLimitUps() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        when(source.fetchMembers("BK1090")).thenThrow(new IllegalStateException("partial"));
        MarketThemeDetail detail = service.detail("BK1090", DATE);
        assertThat(detail.membershipComplete()).isFalse();
        assertThat(detail.memberCount()).isNull();
        assertThat(detail.limitUpCount()).isNull();
        assertThat(detail.maxConsecutiveBoards()).isNull();
        assertThat(detail.limitUpFetchedAt()).isNull();
        verifyNoInteractions(limitUps);
    }

    @Test
    void duplicateOrShortMembershipCannotProduceConfirmedCounts() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 2,
                List.of(member("600001", NOW), member("600001", NOW))));
        assertThat(service.detail("BK1090", DATE).membershipComplete()).isFalse();
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 2,
                List.of(member("600001", NOW))));
        assertThat(service.detail("BK1090", DATE).limitUpCount()).isNull();
        verifyNoInteractions(limitUps);
    }

    @Test
    void futureMemberTimeIsNullAndCurrentMembershipCannotEstablishHistoricalCounts() {
        LocalDate historical = DATE.minusDays(3);
        boards(board("BK1090", "机器人概念", "3", NOW.minusSeconds(86400 * 3)));
        when(source.fetchMembers("BK1090")).thenReturn(new EastMoneyConceptClient.MemberSnapshot(NOW, 2,
                List.of(member("600001", NOW.plusSeconds(1)), member("600002", NOW.minusSeconds(86400 * 3)))));

        MarketThemeDetail detail = service.detail("BK1090", historical);

        assertThat(detail.members().stream().filter(member -> member.symbol().equals("600001")).findFirst().orElseThrow().marketTimestamp()).isNull();
        assertThat(detail.members().stream().filter(member -> member.symbol().equals("600002")).findFirst().orElseThrow().marketTimestamp())
                .isEqualTo(NOW.minusSeconds(86400 * 3));
        assertThat(detail.membershipComplete()).isTrue();
        assertThat(detail.memberCount()).isEqualTo(2);
        assertThat(detail.limitUpCount()).isNull();
        assertThat(detail.maxConsecutiveBoards()).isNull();
        assertThat(detail.limitUpFetchedAt()).isNull();
        assertThat(detail.limitUpStocks()).isEmpty();
        assertThat(detail.dataGaps()).anyMatch(gap -> gap.contains("成分为当前归属") && gap.contains("2026-10-23") && gap.contains("无法确认该日题材涨停统计"));
        verifyNoInteractions(limitUps);
    }

    @Test
    void invalidThemeOrRequestedDateNeverReachesMembershipSource() {
        boards(board("BK1090", "机器人概念", "3", NOW));
        assertThatThrownBy(() -> service.detail("BK1090", DATE.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("刷新");
        assertThatThrownBy(() -> service.detail("BK0596", DATE)).isInstanceOf(IllegalArgumentException.class);
        verify(source, never()).fetchMembers(anyString());
        verifyNoInteractions(limitUps);
    }

    private void boards(EastMoneyConceptClient.Board... values) {
        doReturn(new EastMoneyConceptClient.BoardSnapshot(NOW, List.of(values))).when(source).fetchBoards();
    }

    private static EastMoneyConceptClient.Board board(String code, String name, String change, Instant time) {
        return new EastMoneyConceptClient.Board(code, name, new BigDecimal(change), new BigDecimal("100000000"), 6, 3, 1, time);
    }

    private static EastMoneyConceptClient.Member member(String symbol, Instant time) {
        return new EastMoneyConceptClient.Member(symbol, "股" + symbol, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ONE, time);
    }

    private static ShortTermLimitUpStock stock(String symbol, int boards) {
        return new ShortTermLimitUpStock(symbol, "股" + symbol, "机械", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, boards, boards, boards, BigDecimal.TEN, null, null, 0);
    }

    private static ShortTermLimitUpBoardSnapshot pool(LocalDate date, List<ShortTermLimitUpStock> stocks) {
        return new ShortTermLimitUpBoardSnapshot(date, NOW, true, null, stocks, false, List.of(), List.of(), null, null, List.of());
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
