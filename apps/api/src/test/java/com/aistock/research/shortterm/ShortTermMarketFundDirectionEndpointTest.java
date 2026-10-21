package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIndustryFundFlowSnapshot;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShortTermMarketFundDirectionEndpointTest {

    private static final String ENDPOINT = "/api/short-term/market-fund-direction";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final TradingClockService clock = mock(TradingClockService.class);
    private final ShortTermService service = new ShortTermService(client, new EvidenceCompletenessService(), clock);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ShortTermController(service, null, null, null, null)).build();

    @Test
    void returnsRankedIndustryFlowsWithoutStartingCandidateScan() throws Exception {
        when(clock.currentMarketDateTime()).thenReturn(TODAY.atTime(14, 45));
        when(client.fetchIndustryFundFlows()).thenReturn(List.of(
                flow("BK0001", "银行", "100", TODAY),
                flow("BK0002", "电子", "300", TODAY),
                flow("BK0003", "煤炭", "-100", TODAY),
                flow("BK0004", "证券", "-500", TODAY)));

        mvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0002"))
                .andExpect(jsonPath("$.topInflows[0].mainNetInflow").value(300))
                .andExpect(jsonPath("$.topInflows[0].concentrationPercent").value(30))
                .andExpect(jsonPath("$.topOutflows[0].code").value("BK0004"))
                .andExpect(jsonPath("$.topOutflows[0].mainNetInflow").value(-500))
                .andExpect(jsonPath("$.coveredIndustryCount").value(4))
                .andExpect(jsonPath("$.expectedIndustryCount").value(4))
                .andExpect(jsonPath("$.coverageRatio").value(1))
                .andExpect(jsonPath("$.tradeDate").exists())
                .andExpect(jsonPath("$.fetchedAt").exists())
                .andExpect(jsonPath("$.sourceName").value("东方财富行业资金流"))
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("本次返回行业范围"))));

        verify(client).fetchIndustryFundFlows();
        verifyNoMoreInteractions(client);
    }

    @Test
    void excludesStaleUndatedAndMissingAmountsWhileCountingRealZero() throws Exception {
        when(clock.currentMarketDateTime()).thenReturn(TODAY.atTime(14, 45));
        when(client.fetchIndustryFundFlows()).thenReturn(List.of(
                flow("BK0001", "有效行业", "100", TODAY),
                flow("BK0002", "缺少金额", null, TODAY),
                flow("BK0003", "真实零值", "0", TODAY),
                flow("BK0004", "昨日行业", "900", TODAY.minusDays(1)),
                flow("BK0005", "日期缺失", "800", null)));

        mvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows.length()").value(1))
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0001"))
                .andExpect(jsonPath("$.topOutflows").isEmpty())
                .andExpect(jsonPath("$.coveredIndustryCount").value(2))
                .andExpect(jsonPath("$.expectedIndustryCount").value(5))
                .andExpect(jsonPath("$.coverageRatio").value(0.4))
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("缺少数据日"))))
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("不一致"))));
    }

    @Test
    void entirelyHistoricalRowsDoNotClaimTodaysDataDate() throws Exception {
        when(clock.currentMarketDateTime()).thenReturn(TODAY.atTime(14, 45));
        when(client.fetchIndustryFundFlows()).thenReturn(List.of(
                flow("BK0001", "昨日行业", "100", TODAY.minusDays(1))));

        mvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows").isEmpty())
                .andExpect(jsonPath("$.topOutflows").isEmpty())
                .andExpect(jsonPath("$.tradeDate").doesNotExist())
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("不一致"))));
    }

    @Test
    void upstreamFailureStaysUnavailableWithNoInventedRankings() throws Exception {
        when(clock.currentMarketDateTime()).thenReturn(TODAY.atTime(14, 45));
        when(client.fetchIndustryFundFlows()).thenThrow(new IllegalStateException("行业接口超时"));

        mvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows").isEmpty())
                .andExpect(jsonPath("$.topOutflows").isEmpty())
                .andExpect(jsonPath("$.tradeDate").doesNotExist())
                .andExpect(jsonPath("$.fetchedAt").doesNotExist())
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("行业接口超时"))));
        verify(client).fetchIndustryFundFlows();
        verifyNoMoreInteractions(client);
    }

    @Test
    void cachedSnapshotExpiresAfterSixtySeconds() throws Exception {
        LocalDateTime start = TODAY.atTime(14, 45);
        when(clock.currentMarketDateTime()).thenReturn(start);
        when(client.fetchIndustryFundFlows()).thenReturn(
                List.of(flow("BK0001", "原始行业", "100", TODAY)),
                List.of(flow("BK0002", "更新行业", "200", TODAY)));

        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0001"));
        when(clock.currentMarketDateTime()).thenReturn(start.plusSeconds(59));
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0001"));
        when(clock.currentMarketDateTime()).thenReturn(start.plusSeconds(60));
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0002"));
        verify(client, times(2)).fetchIndustryFundFlows();
        verifyNoMoreInteractions(client);
    }

    @Test
    void newMarketDateInvalidatesCachedSnapshotBeforeSixtySeconds() throws Exception {
        when(clock.currentMarketDateTime()).thenReturn(TODAY.atTime(23, 59, 50));
        when(client.fetchIndustryFundFlows()).thenReturn(
                List.of(flow("BK0001", "原始行业", "100", TODAY)), List.of());
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows[0].code").value("BK0001"));

        when(clock.currentMarketDateTime()).thenReturn(TODAY.plusDays(1).atStartOfDay());
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topInflows").isEmpty())
                .andExpect(jsonPath("$.tradeDate").doesNotExist())
                .andExpect(jsonPath("$.dataGaps", hasItem(containsString("行业资金流为空"))));
        verify(client, times(2)).fetchIndustryFundFlows();
        verifyNoMoreInteractions(client);
    }

    private EastMoneyIndustryFundFlowSnapshot flow(String code, String name, String amount, LocalDate date) {
        return new EastMoneyIndustryFundFlowSnapshot(code, name, amount == null ? null : new BigDecimal(amount),
                BigDecimal.ONE, null, null, null, null, 3, 2, 5,
                "东方财富行业资金流", "https://example.com/industry/" + code,
                Instant.parse("2026-10-21T06:45:00Z"), date,
                date == null ? null : date.atTime(14, 45).atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant());
    }
}
