package com.aistock.research.valuation;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.financial.FinancialHistoryService;
import com.aistock.research.financial.FinancialMetricPoint;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValuationHistoryServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final FinancialHistoryService financialHistoryService = mock(FinancialHistoryService.class);
    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final ValuationHistoryService service = new ValuationHistoryService(
            companyService,
            financialHistoryService,
            eastMoneyClient
    );

    @Test
    void shouldBuildAnnualValuationPercentilesFromKlineAndFinancialHistory() {
        CompanyProfile company = company();
        when(companyService.getCompany("600900")).thenReturn(company);
        when(companyService.listCompanies()).thenReturn(List.of(
                company,
                peer("600905", "三峡能源", "电力", "GREEN_POWER", "18.00", "2.10"),
                peer("601985", "中国核电", "电力", "GREEN_POWER", "16.00", "1.80"),
                peer("600795", "国电电力", "电力", "GREEN_POWER", "13.00", "1.30")
        ));
        when(financialHistoryService.history(eq(company), eq(10))).thenReturn(financialHistory(company));
        when(eastMoneyClient.fetchDailyKLines(eq("600900"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        kline("2021-12-31", "22.00"),
                        kline("2022-12-30", "21.00"),
                        kline("2023-12-29", "23.00"),
                        kline("2024-12-31", "28.00"),
                        kline("2025-12-31", "30.00")
                ));

        ValuationHistoryReport report = service.history("600900", 10);

        assertThat(report.sampleCount()).isEqualTo(5);
        assertThat(report.points()).hasSize(5);
        assertThat(report.currentPe()).isEqualByComparingTo("22.00");
        assertThat(report.pePercentile()).isNotNull();
        assertThat(report.pbPercentile()).isNotNull();
        assertThat(report.status()).isIn("LOW_PERCENTILE", "MID_PERCENTILE", "HIGH_PERCENTILE");
        assertThat(report.peerValuation().scope()).isEqualTo("INDUSTRY");
        assertThat(report.peerValuation().peerCount()).isEqualTo(3);
        assertThat(report.peerValuation().medianPe()).isEqualByComparingTo("16.00");
        assertThat(report.peerValuation().pePeerPercentile()).isNotNull();
        assertThat(report.dataGaps()).anyMatch(gap -> gap.contains("日频"));
    }

    @Test
    void shouldUseIndustryBoardConstituentsWhenCompanyPoolHasTooFewPeers() {
        CompanyProfile company = internetCompany();
        when(companyService.getCompany("002027")).thenReturn(company);
        when(companyService.listCompanies()).thenReturn(List.of(company));
        when(financialHistoryService.history(eq(company), eq(10))).thenReturn(emptyFinancialHistory(company));
        when(eastMoneyClient.fetchIndustryBoardConstituents(eq("互联网服务"), eq(240))).thenReturn(List.of(
                quote("300059", "东方财富", "互联网服务", "35.00", "4.20"),
                quote("300033", "同花顺", "互联网服务", "42.00", "6.10"),
                quote("601360", "三六零", "互联网服务", "28.00", "2.80")
        ));

        ValuationHistoryReport report = service.history("002027", 10);

        assertThat(report.peerValuation().scope()).isEqualTo("INDUSTRY");
        assertThat(report.peerValuation().peerCount()).isEqualTo(3);
        assertThat(report.peerValuation().medianPe()).isEqualByComparingTo("35.00");
        assertThat(report.peerValuation().dataGaps()).noneMatch(gap -> gap.contains("完整行业成分股"));
    }

    private FinancialHistoryReport financialHistory(CompanyProfile company) {
        List<FinancialMetricPoint> points = List.of(
                point(company, "2025-12-31 00:00:00", "1.50", "10.00"),
                point(company, "2025-06-30 00:00:00", "9.00", "30.00"),
                point(company, "2024-12-31 00:00:00", "1.35", "9.50"),
                point(company, "2023-12-31 00:00:00", "1.20", "9.00"),
                point(company, "2022-12-31 00:00:00", "1.10", "8.80"),
                point(company, "2021-12-31 00:00:00", "1.00", "8.40")
        );
        return new FinancialHistoryReport(
                company.symbol(),
                company.name(),
                "USABLE_SEQUENCE",
                "多年序列可用",
                points.size(),
                new BigDecimal("78.00"),
                new BigDecimal("0.12"),
                new BigDecimal("0.40"),
                new BigDecimal("0.08"),
                new BigDecimal("0.10"),
                5,
                0,
                points,
                List.of("已获取 5 个年度财务样本"),
                List.of(),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private FinancialHistoryReport emptyFinancialHistory(CompanyProfile company) {
        return new FinancialHistoryReport(
                company.symbol(),
                company.name(),
                "MISSING_SEQUENCE",
                "财务序列缺失",
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                List.of(),
                List.of(),
                List.of("单元测试模拟缺口"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private FinancialMetricPoint point(CompanyProfile company, String reportDate, String eps, String bps) {
        return new FinancialMetricPoint(
                company.symbol(),
                company.name(),
                reportDate,
                reportDate.substring(0, 4) + "年 年报",
                new BigDecimal("0.12"),
                new BigDecimal("2.00"),
                new BigDecimal("0.40"),
                new BigDecimal("0.08"),
                new BigDecimal("0.10"),
                new BigDecimal(eps),
                new BigDecimal(bps)
        );
    }

    private EastMoneyKLine kline(String date, String close) {
        return new EastMoneyKLine(
                "600900",
                LocalDate.parse(date),
                new BigDecimal(close),
                new BigDecimal(close),
                new BigDecimal(close),
                new BigDecimal(close),
                new BigDecimal("100000"),
                new BigDecimal("300000000")
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

    private CompanyProfile internetCompany() {
        return new CompanyProfile(
                "002027",
                "分众传媒",
                "深交所",
                "互联网服务",
                "DIGITAL_INFRA",
                new BigDecimal("82.00"),
                new BigDecimal("8.00"),
                BigDecimal.ZERO,
                new BigDecimal("20.00"),
                new BigDecimal("4.00"),
                BigDecimal.ONE,
                new BigDecimal("1200000000"),
                "https://quote.example.com/002027",
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("互联网服务行业样本"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of()
        );
    }

    private CompanyProfile peer(String symbol, String name, String industry, String themeCode, String pe, String pb) {
        return new CompanyProfile(
                symbol,
                name,
                "上交所",
                industry,
                themeCode,
                new BigDecimal("70.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal(pe),
                new BigDecimal(pb),
                BigDecimal.ONE,
                new BigDecimal("800000000"),
                "https://quote.example.com/" + symbol,
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("同行业样本"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of()
        );
    }

    private EastMoneyQuote quote(String symbol, String name, String industry, String pe, String pb) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                industry,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal("800000000"),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "东方财富行业成分股",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-06-21T09:00:00Z")
        );
    }
}
