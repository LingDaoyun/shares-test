package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ShortTermLimitUpIndustryServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Instant TIME = Instant.parse("2026-10-08T07:00:00Z");
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final ShortTermLimitUpBoardService service = spy(new ShortTermLimitUpBoardService(client));

    @Test
    void keepsAllLimitUpStocksAndOnlyTheTopThirtyRisingNonLimitUpStocks() {
        List<ShortTermLimitUpStock> sealed = IntStream.range(0, 41)
                .mapToObj(i -> sealed(String.format("600%03d", i), "半导体")).toList();
        List<ShortTermLimitUpStock> pool = new ArrayList<>(sealed);
        pool.add(sealed("000001", "银行"));
        board(pool);
        List<EastMoneyQuote> rows = new ArrayList<>(IntStream.range(100, 140)
                .mapToObj(i -> quote(String.format("600%03d", i), BigDecimal.valueOf(i - 99, 1).toPlainString(), DATE)).toList());
        rows.add(quote("600000", "10", DATE));
        rows.add(quote("000001", "10", DATE));
        rows.add(quote("600999", "-1", DATE));
        rows.add(quote("600998", "0", DATE));
        rows.add(quote("600997", "9", DATE.minusDays(1)));
        rows.add(rows.get(0));
        when(client.fetchIndustryBoardQuoteSnapshot("半导体")).thenReturn(snapshot(rows, true));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("半导体", DATE);

        assertThat(result.tradeDate()).isEqualTo(DATE);
        assertThat(result.limitUpCount()).isEqualTo(41);
        assertThat(result.limitUpStocks()).hasSize(41);
        assertThat(result.risingAvailable()).isTrue();
        assertThat(result.risingStocks()).hasSize(30);
        assertThat(result.risingStocks()).extracting(ShortTermIndustryRisingStock::symbol)
                .startsWith("600139", "600138").endsWith("600110")
                .doesNotContain("600000", "000001", "600999", "600998", "600997");
    }

    @Test
    void industryQuoteFailureKeepsTheKnownLimitUpCountAndStocks() {
        board(List.of(sealed("600001", "半导体")));
        when(client.fetchIndustryBoardQuoteSnapshot("半导体")).thenThrow(new IllegalStateException("连接失败"));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("半导体", DATE);

        assertThat(result.limitUpCount()).isEqualTo(1);
        assertThat(result.limitUpStocks()).hasSize(1);
        assertThat(result.risingAvailable()).isFalse();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("连接失败"));
    }

    @Test
    void incompleteIndustryCoverageDoesNotClaimTheTopThirty() {
        board(List.of(sealed("600001", "半导体")));
        when(client.fetchIndustryBoardQuoteSnapshot("半导体"))
                .thenReturn(snapshot(List.of(quote("600100", "3", DATE)), false));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("半导体", DATE);

        assertThat(result.risingAvailable()).isFalse();
        assertThat(result.risingStocks()).isEmpty();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("覆盖不足"));
    }

    @Test
    void currentQuotesCannotBeMixedIntoAnOlderLimitUpPool() {
        board(List.of(sealed("600001", "半导体")));
        when(client.fetchIndustryBoardQuoteSnapshot("半导体"))
                .thenReturn(snapshot(List.of(quote("600100", "3", DATE.plusDays(1))), true));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("半导体", DATE);

        assertThat(result.risingAvailable()).isFalse();
        assertThat(result.risingStocks()).isEmpty();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("日期"));
    }

    @Test
    void aValidIndustryWithNoRisingStocksReturnsAnAvailableEmptyList() {
        board(List.of(sealed("600001", "半导体")));
        when(client.fetchIndustryBoardQuoteSnapshot("半导体"))
                .thenReturn(snapshot(List.of(quote("600100", "-3", DATE)), true));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("半导体", DATE);

        assertThat(result.risingAvailable()).isTrue();
        assertThat(result.risingStocks()).isEmpty();
    }

    @Test
    void unclassifiedStocksAreKeptWithoutGuessingAnIndustryMembership() {
        board(List.of(sealed("600001", null)));

        ShortTermLimitUpIndustrySnapshot result = service.industrySnapshot("未分类", DATE);

        assertThat(result.limitUpCount()).isEqualTo(1);
        assertThat(result.risingAvailable()).isFalse();
        verify(client, never()).fetchIndustryBoardQuoteSnapshot(anyString());
    }

    @Test
    void anUnavailableLimitUpPoolCannotReportZeroAsAConfirmedCount() {
        doReturn(ShortTermLimitUpBoardSnapshot.unavailable(DATE, TIME, "涨停池失败")).when(service).snapshot(DATE);
        assertThatThrownBy(() -> service.industrySnapshot("半导体", DATE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("涨停池失败");
    }

    private void board(List<ShortTermLimitUpStock> rows) {
        doReturn(new ShortTermLimitUpBoardSnapshot(DATE, TIME, true, null, rows, true,
                List.of(), List.of(), null, null, List.of())).when(service).snapshot(DATE);
    }
    private static AshareQuoteSnapshot snapshot(List<EastMoneyQuote> rows, boolean complete) {
        return new AshareQuoteSnapshot(rows, 6000, rows.size(), rows.size(), 0, complete, "东方财富行业行情", TIME);
    }
    private static ShortTermLimitUpStock sealed(String symbol, String industry) {
        return new ShortTermLimitUpStock(symbol, "涨停" + symbol, industry, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE, 1, 1, 1, BigDecimal.TEN, null, null, 0);
    }
    private static EastMoneyQuote quote(String symbol, String change, LocalDate date) {
        return new EastMoneyQuote(symbol, "上涨" + symbol, "A股", "半导体", BigDecimal.TEN,
                new BigDecimal(change), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100000000"),
                null, null, null, "东方财富行业行情", "", TIME, date,
                date.atTime(15, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
    }
}
