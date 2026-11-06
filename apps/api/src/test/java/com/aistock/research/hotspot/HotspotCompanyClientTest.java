package com.aistock.research.hotspot;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.aistock.research.hotspot.HotspotTestData.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotspotCompanyClientTest {
    private final EastMoneyClient quotes = mock(EastMoneyClient.class);
    private final HotspotCompanyClient client = new HotspotCompanyClient(quotes, new ObjectMapper());
    private final Instant time = Instant.parse("2026-10-19T03:00:00Z");

    @Test
    void acceptsOnlyRequestedAshareIdentitiesAndUsesFallbackForMissingQuotes() {
        when(quotes.fetchTencentQuotes(anyList(), anyInt())).thenReturn(List.of(
                quote("600001", "样本公司", "10", time), quote("600002", "ST风险", "2", time),
                quote("600003", "退市样本", "1", time), quote("600999", "未请求公司", "9", time)));
        when(quotes.fetchEastMoneyQuotesBySymbols(anyList(), anyInt())).thenReturn(List.of(
                quote("600004", "另一公司", "20", time)));
        List<String> gaps = new ArrayList<>();

        var result = client.quoteIndex(List.of("600001", "600001", "600002", "600003", "600004", "AAPL", "123456"), gaps);

        assertThat(result).containsOnlyKeys("600001", "600004");
        verify(quotes).fetchTencentQuotes(List.of("600001", "600002", "600003", "600004"), 4);
        verify(quotes).fetchEastMoneyQuotesBySymbols(List.of("600002", "600003", "600004"), 3);
        assertThat(gaps).anyMatch(gap -> gap.contains("部分股票行情暂不可用"));
    }

    @Test
    void rejectsModelCandidateWhenItsNameDoesNotMatchVerifiedSymbol() {
        when(quotes.fetchTencentQuotes(anyList(), anyInt())).thenReturn(List.of(quote("600001", "真实简称", "10", time)));
        List<String> gaps = new ArrayList<>();

        var result = client.fetch(List.of(new HotspotCompanyClient.Candidate("600001", "幻觉简称")), gaps);

        assertThat(result).isEmpty();
        assertThat(gaps).anyMatch(gap -> gap.contains("未通过真实行情代码、名称核对"));
        verify(quotes, never()).fetchEastMoneyQuotesBySymbols(anyList(), anyInt());
    }

    @Test
    void fetchedAtCannotSubstituteForMissingMarketTimestamp() {
        var unknownTime = new EastMoneyQuote("600001", "样本公司", "SH", "电池", BigDecimal.TEN,
                null, null, null, null, null, null, null, "行情", "https://quote.example", time);
        var tradeDateOnly = new EastMoneyQuote("600001", "样本公司", "SH", "电池", BigDecimal.TEN,
                null, null, null, null, null, null, null, "行情", "https://quote.example", time,
                LocalDate.of(2026, 10, 16), null);

        assertThat(HotspotCompanyClient.quoteAsOf(unknownTime)).isNull();
        assertThat(HotspotCompanyClient.quoteAsOf(tradeDateOnly)).isEqualTo("2026-10-16（交易日，时分未知）");
        assertThat(HotspotCompanyClient.quoteAsOf(quote("600001", "样本公司", "10", time))).isEqualTo(time.toString());
    }
}
