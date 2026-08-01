package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyThemeServiceTest {

    private final GovPolicyClient govPolicyClient = mock(GovPolicyClient.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final PolicyThemeService service = new PolicyThemeService(govPolicyClient, companyService, eastMoneyClient);

    @Test
    void buildsAResearchOnlyLeaderCompanyPoolForEachPolicyTheme() {
        when(govPolicyClient.fetchLatestPolicies(40)).thenReturn(List.of(
                new GovPolicyItem("中国政府网", "json", "关于推动新质生产力和高端制造发展的规划",
                        "https://www.gov.cn/zhengce/example", "2026-07-20", 100)
        ));
        when(companyService.listCompanies()).thenReturn(List.of(
                profile("600031", "三一重工", "工程机械", "NEW_QUALITY_PRODUCTIVITY", "92", "5000000000", "0.18", "1.20", "0.11"),
                profile("000333", "美的集团", "白色家电", "NEW_QUALITY_PRODUCTIVITY", "85", "4500000000", "0.22", "1.80", "0.15"),
                profile("688999", "样本亏损", "高端装备", "NEW_QUALITY_PRODUCTIVITY", "95", "3000000000", "-0.02", "0.50", "-0.10"),
                profile("300750", "宁德时代", "电池", "GREEN_TRANSITION", "90", "8000000000", "0.20", "2.10", "0.12")
        ));

        PolicyTheme theme = service.listThemes().stream()
                .filter(item -> "NEW_QUALITY_PRODUCTIVITY".equals(item.themeCode()))
                .findFirst()
                .orElseThrow();

        assertThat(theme.companyPool()).extracting(PolicyCompanyCandidate::symbol)
                .containsExactly("600031", "000333", "688999");
        assertThat(theme.companyPool()).allSatisfy(candidate -> {
            assertThat(candidate.researchRole()).contains("研究候选");
            assertThat(candidate.actionLabel()).isEqualTo("不荐股");
            assertThat(candidate.financialQualityLabel()).isNotBlank();
            assertThat(candidate.leadershipRationale()).isNotEmpty();
        });
        assertThat(theme.companyPool().get(0).financialQualityLabel()).contains("财报质量较好");
        assertThat(theme.companyPool().get(2).dataGaps()).contains("最近年度 ROE 或现金流质量不足，需要继续核验");
    }

    @Test
    void enrichesFastQuoteCandidatesWithAnnualFinancialsBeforeRanking() {
        when(govPolicyClient.fetchLatestPolicies(40)).thenReturn(List.of(
                new GovPolicyItem("中国政府网", "json", "数字基础设施和算力建设行动方案",
                        "https://www.gov.cn/zhengce/digital", "2026-07-20", 100)
        ));
        when(companyService.listCompanies()).thenReturn(List.of(
                profileWithoutFinance("000977", "浪潮信息", "计算机设备", "DIGITAL_INFRA", "90", "5000000000"),
                profileWithoutFinance("688041", "海光信息", "半导体", "DIGITAL_INFRA", "94", "8000000000")
        ));
        when(eastMoneyClient.fetchAnnualIndicatorHistory("000977", 1)).thenReturn(List.of(annual("000977", "0.16", "2.00", "0.18")));
        when(eastMoneyClient.fetchAnnualIndicatorHistory("688041", 1)).thenReturn(List.of(annual("688041", "0.04", "-0.20", "0.08")));

        PolicyTheme theme = service.listThemes().stream()
                .filter(item -> "DIGITAL_INFRA".equals(item.themeCode()))
                .findFirst()
                .orElseThrow();

        assertThat(theme.companyPool()).extracting(PolicyCompanyCandidate::symbol)
                .containsExactly("000977", "688041");
        assertThat(theme.companyPool().get(0).financialQualityLabel()).isEqualTo("财报质量较好");
        assertThat(theme.companyPool().get(1).financialQualityLabel()).isEqualTo("财报质量待核验");
    }

    private CompanyProfile profile(
            String symbol,
            String name,
            String industry,
            String themeCode,
            String relevance,
            String amount,
            String roe,
            String ocfps,
            String profitGrowth
    ) {
        return new CompanyProfile(
                symbol,
                name,
                "上交所",
                industry,
                themeCode,
                new BigDecimal(relevance),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                new BigDecimal("20"),
                new BigDecimal("2"),
                new BigDecimal("3"),
                new BigDecimal(amount),
                "https://quote.example.com/" + symbol,
                "测试行情",
                "2026-07-30T07:00:00Z",
                "2025-12-31",
                "年报",
                true,
                List.of("行业成交额靠前", "主题匹配"),
                List.of(),
                Map.of(
                        "roe_annual", new BigDecimal(roe),
                        "operating_cash_flow_per_share", new BigDecimal(ocfps),
                        "net_profit_growth", new BigDecimal(profitGrowth),
                        "policy_theme_relevance", new BigDecimal(relevance).divide(new BigDecimal("100")),
                        "amount", new BigDecimal(amount)
                ),
                List.of()
        );
    }

    private CompanyProfile profileWithoutFinance(
            String symbol,
            String name,
            String industry,
            String themeCode,
            String relevance,
            String amount
    ) {
        return new CompanyProfile(
                symbol,
                name,
                "深交所",
                industry,
                themeCode,
                new BigDecimal(relevance),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                new BigDecimal("20"),
                new BigDecimal("2"),
                new BigDecimal("3"),
                new BigDecimal(amount),
                "https://quote.example.com/" + symbol,
                "测试行情",
                "2026-07-30T07:00:00Z",
                null,
                null,
                true,
                List.of("行业成交额靠前", "主题匹配"),
                List.of(),
                Map.of(
                        "policy_theme_relevance", new BigDecimal(relevance).divide(new BigDecimal("100")),
                        "amount", new BigDecimal(amount)
                ),
                List.of()
        );
    }

    private EastMoneyAnnualIndicator annual(String symbol, String roe, String ocfps, String profitGrowth) {
        return new EastMoneyAnnualIndicator(
                symbol,
                "样本",
                "2025-12-31",
                "年报",
                new BigDecimal(roe),
                new BigDecimal(ocfps),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"),
                new BigDecimal(profitGrowth),
                new BigDecimal("1.00"),
                new BigDecimal("8.00")
        );
    }
}
