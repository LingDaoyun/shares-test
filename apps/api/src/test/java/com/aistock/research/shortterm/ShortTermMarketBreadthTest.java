package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ShortTermMarketBreadthTest {
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final ShortTermLimitUpBoardService service = new ShortTermLimitUpBoardService(client);
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void countsDirectionsAndExcludesMissingOrWrongDayQuotes() {
        List<EastMoneyQuote> quotes = List.of(
                quote("600001", today, "10", "2.1"),
                quote("600002", today, "10", "-3"),
                quote("600003", today, "10", "0"),
                quote("600004", today, null, "0"),
                quote("600005", today, "10", null),
                quote("600006", today.minusDays(1), "10", "5"),
                quote("600007", null, "10", "5"),
                quote("600008", today, "0", "0"));
        AshareQuoteSnapshot source = snapshot(quotes, quotes.size(), true);
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(source);

        ShortTermMarketBreadth result = service.marketBreadth();

        assertThat(result.advancing()).isEqualTo(1);
        assertThat(result.declining()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(5);
        assertThat(result.totalCount()).isEqualTo(8);
        assertThat(result.tradeDate()).isEqualTo(today);
        assertThat(result.fetchedAt()).isEqualTo(source.fetchedAt());
        assertThat(result.source()).isEqualTo("东方财富行情");
        verifyNoMoreInteractionsAfterQuoteFetch();
    }

    @Test
    void reusesRecentSnapshotWithoutFetchingAgainOrStartingScan() {
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(snapshot(List.of(quote("600001", today, "10", "2")), 1, true));

        ShortTermMarketBreadth first = service.marketBreadth();

        assertThat(service.marketBreadth()).isSameAs(first);
        verifyNoMoreInteractionsAfterQuoteFetch();
    }

    @Test
    void publishesOnlyCountedQuotesSourceTimeRangeSeparatelyFromRetrievalTime() {
        Instant early = today.atTime(14, 29).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant late = today.atTime(15, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant retrieved = late.plusSeconds(3600);
        List<EastMoneyQuote> quotes = List.of(
                quote("600001", today, "10", "2", early),
                quote("600002", today, "10", "-1", late),
                quote("600003", today, "10", "0", null),
                quote("600004", today, null, "2", early.minusSeconds(3600)),
                quote("600005", today, "10", null, retrieved),
                quote("600006", today.minusDays(1), "10", "2", early.minusSeconds(86400)));
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(new AshareQuoteSnapshot(
                quotes, Integer.MAX_VALUE, 6, 6, 0, true, "东方财富行情", retrieved));

        ShortTermMarketBreadth result = service.marketBreadth();
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(result);

        assertThat(json.path("marketTimestampFrom").asText(null)).isEqualTo(early.toString());
        assertThat(json.path("marketTimestampTo").asText(null)).isEqualTo(late.toString());
        assertThat(json.path("missingTimestampCount").asInt(-1)).isEqualTo(1);
        assertThat(result.fetchedAt()).isEqualTo(retrieved);
        assertThat(result.advancing()).isEqualTo(1);
        assertThat(result.declining()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(3);
        verifyNoMoreInteractionsAfterQuoteFetch();
    }

    @Test
    void leavesSourceTimeUnknownWhenEveryCountedQuoteLacksATimestamp() {
        List<EastMoneyQuote> quotes = List.of(
                quote("600001", today, "10", "2", null),
                quote("600002", today, "10", "-1", null),
                quote("600003", today, null, "2", Instant.now()));
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE)).thenReturn(snapshot(quotes, 3, true));

        ShortTermMarketBreadth result = service.marketBreadth();
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);

        assertThat(json.path("marketTimestampFrom").isNull()).isTrue();
        assertThat(json.path("marketTimestampTo").isNull()).isTrue();
        assertThat(json.path("missingTimestampCount").asInt(-1)).isEqualTo(2);
        assertThat(result.advancing()).isEqualTo(1);
        assertThat(result.declining()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(1);
        verifyNoMoreInteractionsAfterQuoteFetch();
    }

    @Test
    void rejectsIncompleteCoverageAndAllowsRetry() {
        List<EastMoneyQuote> quotes = List.of(quote("600001", today, "10", "2"));
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(snapshot(quotes, 2, false), snapshot(quotes, 1, true));

        assertThatThrownBy(service::marketBreadth).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("覆盖不足").hasMessageContaining("1/2");
        assertThat(service.marketBreadth().advancing()).isEqualTo(1);
        verify(client, times(2)).fetchAshareQuoteSnapshot(Integer.MAX_VALUE);
    }

    @Test
    void rejectsInconsistentTotalEvenWhenProviderMarksSnapshotComplete() {
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(snapshot(List.of(quote("600001", today, "10", "2")), 2, true));

        assertThatThrownBy(service::marketBreadth).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("覆盖不足");
    }

    @Test
    void doesNotPresentYesterdayQuotesAsTodayOrInventZeroCounts() {
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenReturn(snapshot(List.of(quote("600001", today.minusDays(1), "10", "2")), 1, true));

        assertThatThrownBy(service::marketBreadth).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚无 " + today).hasMessageContaining("有效涨跌报价");
    }

    @Test
    void surfacesUpstreamFailureAndAllowsRetry() {
        when(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .thenThrow(new IllegalStateException("行情源超时"))
                .thenReturn(snapshot(List.of(quote("600001", today, "10", "-2")), 1, true));

        assertThatThrownBy(service::marketBreadth).hasMessageContaining("行情源超时");
        assertThat(service.marketBreadth().declining()).isEqualTo(1);
    }

    private void verifyNoMoreInteractionsAfterQuoteFetch() {
        verify(client).fetchAshareQuoteSnapshot(Integer.MAX_VALUE);
        verifyNoMoreInteractions(client);
    }

    private AshareQuoteSnapshot snapshot(List<EastMoneyQuote> quotes, int expected, boolean complete) {
        return new AshareQuoteSnapshot(quotes, Integer.MAX_VALUE, expected, quotes.size(),
                Math.max(0, expected - quotes.size()), complete, "东方财富行情", Instant.now());
    }

    private EastMoneyQuote quote(String symbol, LocalDate date, String price, String change) {
        return quote(symbol, date, price, change, Instant.now());
    }

    private EastMoneyQuote quote(String symbol, LocalDate date, String price, String change, Instant marketTimestamp) {
        return new EastMoneyQuote(symbol, "样本", "SH", null,
                price == null ? null : new BigDecimal(price),
                change == null ? null : new BigDecimal(change),
                null, null, null, null, null, null,
                "东方财富行情", null, Instant.now(), date, marketTimestamp);
    }
}
