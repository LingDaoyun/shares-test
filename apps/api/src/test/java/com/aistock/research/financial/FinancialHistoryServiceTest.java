package com.aistock.research.financial;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialHistoryServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final FinancialHistoryService service = new FinancialHistoryService(companyService, eastMoneyClient);

    @Test
    void shouldBuildFinancialHistoryFromAnnualIndicators() {
        CompanyProfile company = company();
        when(companyService.getCompany("600900")).thenReturn(company);
        when(eastMoneyClient.fetchAnnualIndicatorHistory("600900", 10)).thenReturn(List.of(
                indicator("2025-12-31", "0.15", "2.40", "0.45", "0.08"),
                indicator("2025-06-30", "0.30", "9.90", "0.90", "0.80"),
                indicator("2024-12-31", "0.14", "2.10", "0.43", "0.07"),
                indicator("2023-12-31", "0.13", "1.90", "0.42", "0.05"),
                indicator("2022-12-31", "0.12", "1.70", "0.40", "0.04"),
                indicator("2021-12-31", "0.11", "1.60", "0.39", "0.03")
        ));

        FinancialHistoryReport report = service.history("600900", 10);

        assertThat(report.symbol()).isEqualTo("600900");
        assertThat(report.annualPointCount()).isEqualTo(5);
        assertThat(report.positiveCashFlowYears()).isEqualTo(5);
        assertThat(report.qualityScore()).isGreaterThan(new BigDecimal("58.00"));
        assertThat(report.status()).isEqualTo("USABLE_SEQUENCE");
        assertThat(report.dataGaps()).anyMatch(gap -> gap.contains("估值分位"));
    }

    private EastMoneyAnnualIndicator indicator(String reportDate, String roe, String cash, String margin, String growth) {
        return new EastMoneyAnnualIndicator(
                "600900",
                "长江电力",
                reportDate,
                reportDate.substring(0, 4) + "年 年报",
                new BigDecimal(roe),
                new BigDecimal(cash),
                new BigDecimal(margin),
                new BigDecimal(growth),
                new BigDecimal("0.06"),
                new BigDecimal("1.20"),
                new BigDecimal("8.80")
        );
    }

    private CompanyProfile company() {
        return new CompanyProfile(
                "600900",
                "长江电力",
                "上交所",
                "电力",
                "GREEN_POWER",
                new BigDecimal("82.00"),
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                new BigDecimal("22.00"),
                new BigDecimal("3.00"),
                BigDecimal.ONE,
                new BigDecimal("1200000000"),
                "https://quote.example.com/600900",
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("水电核心资产", "稳定现金流"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of()
        );
    }
}
