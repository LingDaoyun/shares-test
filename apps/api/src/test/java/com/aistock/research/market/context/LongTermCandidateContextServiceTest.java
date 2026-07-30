package com.aistock.research.market.context;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTermCandidateContextServiceTest {

    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final LongTermPolicyEvidenceService policyService = mock(LongTermPolicyEvidenceService.class);
    private final LongTermCycleContextService cycleService = new LongTermCycleContextService();
    private final LongTermCandidateContextService service =
            new LongTermCandidateContextService(eastMoneyClient, policyService, cycleService);

    @Test
    void usesServerIndustryAndRecordsMismatchAgainstScanIndustry() {
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of("600795"), 1))
                .thenReturn(List.of(quote("600795", "国电电力", "电力")));
        when(eastMoneyClient.fetchAnnualIndicatorHistory("600795", 5)).thenReturn(List.of());
        when(eastMoneyClient.fetchDailyKLines(eq("600795"), any(), any())).thenReturn(List.of());
        when(policyService.evaluate("电力", "国电电力"))
                .thenReturn(new LongTermPolicyEvidence(List.of(), List.of()));

        LongTermCandidateContext context = service.load("600795", "煤炭");

        assertThat(context.industry()).isEqualTo("电力");
        assertThat(context.companyName()).isEqualTo("国电电力");
        assertThat(context.dataGaps()).contains("扫描行业“煤炭”与实时行业“电力”不一致，已采用实时行业");
        assertThat(context.cycleContext().businessStage()).isEqualTo("INSUFFICIENT");
    }

    @Test
    void keepsPartialContextWhenQuoteFinancialAndKLineSourcesFail() {
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(any(), anyInt()))
                .thenThrow(new IllegalStateException("行情失败"));
        when(eastMoneyClient.fetchStockBoardIndustry("600795"))
                .thenThrow(new IllegalStateException("行业失败"));
        when(eastMoneyClient.fetchAnnualIndicatorHistory("600795", 5))
                .thenThrow(new IllegalStateException("财报失败"));
        when(eastMoneyClient.fetchDailyKLines(eq("600795"), any(), any()))
                .thenThrow(new IllegalStateException("K线失败"));
        when(policyService.evaluate("行业待补", "600795"))
                .thenReturn(new LongTermPolicyEvidence(List.of(), List.of("政策失败")));

        LongTermCandidateContext context = service.load("600795", "电力");

        assertThat(context.symbol()).isEqualTo("600795");
        assertThat(context.industry()).isEqualTo("行业待补");
        assertThat(context.cycleContext().businessStage()).isEqualTo("INSUFFICIENT");
        assertThat(context.dataGaps()).contains("服务端实时行业未确认；扫描行业“电力”仅用于回显，未作为事实证据");
        assertThat(context.dataGaps()).anyMatch(gap -> gap.contains("实时行情暂不可用"));
        assertThat(context.dataGaps()).anyMatch(gap -> gap.contains("年度财务暂不可用"));
        assertThat(context.dataGaps()).anyMatch(gap -> gap.contains("K线暂不可用"));
        assertThat(context.policyEvidence().dataGaps()).contains("政策失败");
    }

    @Test
    void acceptsNorthExchangeSymbolsBeginningWithFour() {
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of("430047"), 1))
                .thenReturn(List.of(quote("430047", "诺思兰德", "生物制品")));
        when(eastMoneyClient.fetchAnnualIndicatorHistory("430047", 5)).thenReturn(List.of());
        when(eastMoneyClient.fetchDailyKLines(eq("430047"), any(), any())).thenReturn(List.of());
        when(policyService.evaluate("生物制品", "诺思兰德"))
                .thenReturn(new LongTermPolicyEvidence(List.of(), List.of()));

        LongTermCandidateContext context = service.load("430047", "生物制品");

        assertThat(context.symbol()).isEqualTo("430047");
    }

    private EastMoneyQuote quote(String symbol, String name, String industry) {
        return new EastMoneyQuote(
                symbol,
                name,
                "沪A",
                industry,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.TEN,
                "东方财富",
                "https://quote.eastmoney.com/" + symbol,
                Instant.now()
        );
    }
}
