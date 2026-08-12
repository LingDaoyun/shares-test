package com.aistock.research.company;

import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyServiceTest {

    @Test
    void listCompaniesUsesLatestFullYearIndicatorsWhenAvailable() {
        EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
        LiveDataProperties properties = properties();
        CompanyService service = new CompanyService(eastMoneyClient, properties);

        int latestFullYear = LocalDate.now().minusYears(1).getYear();
        EastMoneyAnnualIndicator indicator = annualIndicator("600519", latestFullYear + "-12-31");
        EastMoneyQuote quote = quote("600519", "贵州茅台");

        when(eastMoneyClient.fetchAnnualIndicators(eq(latestFullYear), anyInt()))
                .thenReturn(Map.of("600519", indicator));
        when(eastMoneyClient.fetchTencentQuotes(anyList(), eq(properties.stockLimit())))
                .thenReturn(List.of(quote));

        CompanyProfile profile = service.listCompanies().get(0);

        assertThat(profile.financialReportDate()).isEqualTo(latestFullYear + "-12-31");
        assertThat(profile.factors()).containsEntry("roe_annual", new BigDecimal("0.22"));
        assertThat(profile.evidence())
                .anyMatch(item -> "年报指标".equals(item.sourceType()) && item.excerpt().contains("ROE 22.00%"));
        verify(eastMoneyClient).fetchAnnualIndicators(eq(latestFullYear), anyInt());
    }

    @Test
    void listCompaniesFallsBackToPreviousYearIndicatorsWhenLatestFullYearIsEmpty() {
        EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
        LiveDataProperties properties = properties();
        CompanyService service = new CompanyService(eastMoneyClient, properties);

        int latestFullYear = LocalDate.now().minusYears(1).getYear();
        int previousYear = latestFullYear - 1;
        EastMoneyAnnualIndicator indicator = annualIndicator("300750", previousYear + "-12-31");
        EastMoneyQuote quote = quote("300750", "宁德时代");

        when(eastMoneyClient.fetchAnnualIndicators(eq(latestFullYear), anyInt()))
                .thenReturn(Map.of());
        when(eastMoneyClient.fetchAnnualIndicators(eq(previousYear), anyInt()))
                .thenReturn(Map.of("300750", indicator));
        when(eastMoneyClient.fetchTencentQuotes(anyList(), eq(properties.stockLimit())))
                .thenReturn(List.of(quote));

        CompanyProfile profile = service.listCompanies().get(0);

        assertThat(profile.financialReportDate()).isEqualTo(previousYear + "-12-31");
        assertThat(profile.factors()).containsEntry("revenue_growth", new BigDecimal("0.18"));
        verify(eastMoneyClient).fetchAnnualIndicators(eq(latestFullYear), anyInt());
        verify(eastMoneyClient).fetchAnnualIndicators(eq(previousYear), anyInt());
    }

    @Test
    void listCompaniesFailsInsteadOfUsingDemoPoolWhenRealtimeQuotesUnavailable() {
        EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
        LiveDataProperties properties = properties();
        CompanyService service = new CompanyService(eastMoneyClient, properties);

        int latestFullYear = LocalDate.now().minusYears(1).getYear();
        int previousYear = latestFullYear - 1;
        when(eastMoneyClient.fetchAnnualIndicators(eq(latestFullYear), anyInt()))
                .thenReturn(Map.of());
        when(eastMoneyClient.fetchAnnualIndicators(eq(previousYear), anyInt()))
                .thenReturn(Map.of());
        when(eastMoneyClient.fetchTencentQuotes(anyList(), eq(properties.stockLimit())))
                .thenThrow(new IllegalStateException("腾讯行情超时"));
        when(eastMoneyClient.fetchLiquidAshareQuotes(eq(properties.stockLimit())))
                .thenThrow(new IllegalStateException("东方财富行情超时"));

        assertThatThrownBy(service::listCompanies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实时公司池加载失败")
                .hasMessageContaining("未使用缓存或示例数据");
    }

    @Test
    void listCompaniesUsesDynamicMarketPoolWhenFinancialCandidatesAreEmpty() {
        EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
        LiveDataProperties properties = properties();
        CompanyService service = new CompanyService(eastMoneyClient, properties);
        int latestFullYear = LocalDate.now().minusYears(1).getYear();
        int previousYear = latestFullYear - 1;
        when(eastMoneyClient.fetchAnnualIndicators(eq(latestFullYear), anyInt())).thenReturn(Map.of());
        when(eastMoneyClient.fetchAnnualIndicators(eq(previousYear), anyInt())).thenReturn(Map.of());
        when(eastMoneyClient.fetchLiquidAshareQuotes(eq(properties.stockLimit())))
                .thenReturn(List.of(quote("688256", "寒武纪")));

        List<CompanyProfile> companies = service.listCompanies();

        assertThat(companies).extracting(CompanyProfile::symbol).containsExactly("688256");
        verify(eastMoneyClient, never()).fetchTencentQuotes(anyList(), anyInt());
    }

    private LiveDataProperties properties() {
        return new LiveDataProperties(
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_URL,
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_MINUTE_URL,
                20,
                "https://quote.example.com",
                "https://financial.example.com",
                "https://cninfo.example.com",
                "https://policy.example.com",
                false,
                12,
                2,
                6
        );
    }

    private EastMoneyAnnualIndicator annualIndicator(String symbol, String reportDate) {
        return new EastMoneyAnnualIndicator(
                symbol,
                "测试公司",
                reportDate,
                "2025年 年报",
                new BigDecimal("0.22"),
                new BigDecimal("3.56"),
                new BigDecimal("0.44"),
                new BigDecimal("0.18"),
                new BigDecimal("0.15"),
                new BigDecimal("8.88"),
                new BigDecimal("22.22")
        );
    }

    private EastMoneyQuote quote(String symbol, String name) {
        return new EastMoneyQuote(
                symbol,
                name,
                "上交所",
                "电池",
                new BigDecimal("123.45"),
                new BigDecimal("1.23"),
                new BigDecimal("0.88"),
                new BigDecimal("100000"),
                new BigDecimal("90000000"),
                new BigDecimal("25.00"),
                new BigDecimal("4.50"),
                new BigDecimal("24.00"),
                "腾讯行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-06-16T00:00:00Z")
        );
    }
}
