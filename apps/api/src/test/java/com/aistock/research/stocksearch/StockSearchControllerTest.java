package com.aistock.research.stocksearch;

import com.aistock.research.common.error.GlobalExceptionHandler;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockSearchControllerTest {
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final SinaStockDirectoryClient directoryClient = mock(SinaStockDirectoryClient.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StockSearchController(new StockSearchService(client, directoryClient)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void exposesSearchResultsAndAcceptsOptionalForcedRefresh() throws Exception {
        var quote = new EastMoneyQuote("600001", "东方股份", "上交所", null,
                new BigDecimal("10"), new BigDecimal("1.5"), new BigDecimal("2.5"),
                null, null, null, null, null, "测试行情", null, Instant.now());
        when(directoryClient.fetch()).thenReturn(new StockDirectorySnapshot(
                List.of(new StockDirectorySnapshot.Stock("600001", "东方股份", "上交所")),
                1, true, "测试目录", Instant.now()));
        when(client.fetchTencentQuotes(List.of("600001"), Integer.MAX_VALUE)).thenReturn(List.of(quote));

        mvc.perform(get("/api/stocks/search").param("keyword", " 东方 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("东方"))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.universeCount").value(1))
                .andExpect(jsonPath("$.expectedCount").value(1))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.source").exists())
                .andExpect(jsonPath("$.fetchedAt").exists())
                .andExpect(jsonPath("$.stocks[0].symbol").value("600001"))
                .andExpect(jsonPath("$.stocks[0].turnoverRate").value(2.5));
        mvc.perform(get("/api/stocks/search").param("keyword", "东方").param("refresh", "true"))
                .andExpect(status().isOk());
        verify(directoryClient, times(2)).fetch();
    }

    @Test
    void rejectsMissingBlankKeywordsAndInvalidRefreshValues() throws Exception {
        mvc.perform(get("/api/stocks/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("keyword", "  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/stocks/search").param("keyword", "东方").param("refresh", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, directoryClient);
    }

    @Test
    void reportsUnavailableSourceThroughExistingErrorContract() throws Exception {
        when(directoryClient.fetch()).thenThrow(new IllegalStateException("行情不可用"));
        mvc.perform(get("/api/stocks/search").param("keyword", "东方"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("行情不可用"));
    }

    @Test
    void allowsAnEmptyKeywordWithFiltersAndReturnsCandidateAndUnverifiedCounts() throws Exception {
        when(directoryClient.fetch()).thenReturn(new StockDirectorySnapshot(List.of(
                new StockDirectorySnapshot.Stock("300001", "创业公司", "深交所"),
                new StockDirectorySnapshot.Stock("600001", "东方股份", "上交所"),
                new StockDirectorySnapshot.Stock("600002", "缺行情股份", "上交所")), 3, true, "测试目录", Instant.now()));
        when(client.fetchTencentQuotes(List.of("600001", "600002"), Integer.MAX_VALUE))
                .thenReturn(List.of(StockSearchFiltersTest.quote("2", "10000000000", "6000000000", "2.5", "10", "150000000")));

        mvc.perform(get("/api/stocks/search").param("excludeChiNext", "true")
                        .param("minChangePercent", "2").param("maxChangePercent", "2")
                        .param("minMarketCap", "100").param("maxMarketCap", "100")
                        .param("minCirculatingMarketCap", "60").param("maxCirculatingMarketCap", "60")
                        .param("minTurnoverRate", "2.5").param("maxTurnoverRate", "2.5")
                        .param("minPrice", "10").param("maxPrice", "10")
                        .param("minAmount", "1.5").param("maxAmount", "1.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value(""))
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.unverifiedCount").value(1))
                .andExpect(jsonPath("$.stocks[0].symbol").value("600001"));
    }

    @Test
    void rejectsInvalidFiltersAndOverlongKeywordsBeforeMarketRequests() throws Exception {
        mvc.perform(get("/api/stocks/search").param("keyword", "东".repeat(81))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("excludeStar", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("minPrice", "wrong")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("minPrice", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("minMarketCap", "20").param("maxMarketCap", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stocks/search").param("excludeStar", "false"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, directoryClient);
    }
}
