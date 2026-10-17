package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class PolicyThemeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private final GovPolicyClient govPolicyClient = mock(GovPolicyClient.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final PolicyThemeService service = new PolicyThemeService(govPolicyClient, companyService, eastMoneyClient, CLOCK);

    @Test
    void corePoolsRequireIndustryEvidenceInsteadOfDefaultThemeOrCompanyName() {
        stubPolicies(List.of(
                policy("高端制造和算力基础设施、绿色能源建设方案", TODAY.toString())));
        when(companyService.listCompanies()).thenReturn(List.of(
                profile("600001", "普通银行", "银行", "NEW_QUALITY_PRODUCTIVITY", "48", "8000000000", "0.20", "1", "0.1"),
                profile("600002", "算力能源集团", "银行", "DIGITAL_INFRA", "90", "8000000000", "0.20", "1", "0.1"),
                profile("600003", "绿色能源", "银行", "GREEN_TRANSITION", "90", "8000000000", "0.20", "1", "0.1"),
                profile("600004", "行业设备样本", "计算机设备", "NEW_QUALITY_PRODUCTIVITY", "48", "2000000000", "0.18", "1", "0.1")
        ));
        List<PolicyTheme> themes = service.listThemes();
        assertThat(themes).flatExtracting(PolicyTheme::companyPool).extracting(PolicyCompanyCandidate::symbol)
                .doesNotContain("600001", "600002", "600003");
        assertThat(themes.stream().filter(row -> row.themeCode().equals("DIGITAL_INFRA")).findFirst().orElseThrow().companyPool())
                .extracting(PolicyCompanyCandidate::symbol).contains("600004");
    }

    @Test
    void preservesSuccessfulPoliciesAndExposesOnlySafeSourceFailureNames() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(40)).thenReturn(new GovPolicyFetchResult(
                List.of(policy("算力基础设施建设规划", TODAY.toString())),
                List.of("工业部门：HTTP 502 body with credential-like detail", "raw exception without source delimiter")));
        when(companyService.listCompanies()).thenReturn(List.of(
                profile("600004", "设备样本", "计算机设备", "DIGITAL_INFRA", "80", "2000000000", "0.18", "1", "0.1")));

        PolicyThemeCollection result = service.listThemesWithStatus();

        assertThat(result.themes().stream().filter(row -> row.themeCode().equals("DIGITAL_INFRA")).findFirst().orElseThrow().signals())
                .hasSize(1);
        assertThat(result.dataGaps()).contains("工业部门：政策源采集失败，本次覆盖不完整。", "部分政策源采集失败，本次覆盖不完整。");
        assertThat(result.dataGaps().toString()).doesNotContain("HTTP", "credential", "raw exception");
    }

    @Test
    void exposesUnavailableCompanyCollectionWithoutLeakingExceptionDetails() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(40)).thenReturn(new GovPolicyFetchResult(
                List.of(policy("算力基础设施建设规划", TODAY.toString())), List.of()));
        when(companyService.listCompanies()).thenThrow(new IllegalStateException("sensitive upstream body"));

        PolicyThemeCollection result = service.listThemesWithStatus();

        assertThat(result.dataGaps()).contains("公司样本未取得有效数据，上市公司关联暂不可用。");
        assertThat(result.dataGaps().toString()).doesNotContain("sensitive");
        assertThat(result.themes()).allSatisfy(theme -> assertThat(theme.companyPool()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesPolicyOnlyIndustryFallbackAfterPrimaryFailureWithoutRepeatingAnnualRequests() {
        PolicyCompanyFallbackClient fallback = mock(PolicyCompanyFallbackClient.class);
        PolicyThemeService target = new PolicyThemeService(govPolicyClient, companyService, eastMoneyClient, CLOCK, fallback);
        stubPolicies(List.of(policy("数字基础设施算力建设规划", TODAY.toString())));
        when(companyService.listCompanies()).thenThrow(new IllegalStateException("primary unavailable"));
        CompanyProfile profile = new CompanyProfile("600001", "电子样本", "上交所", "电子信息", "UNCLASSIFIED",
                BigDecimal.ZERO, null, null, null, null, null, null, "https://vip.stock.finance.sina.com.cn/source",
                PolicyCompanyFallbackClient.SOURCE_NAME, "2026-10-17T04:00:00Z", null, null, false,
                List.of(), List.of(), Map.of(), List.of());
        when(fallback.fetch(anyList())).thenReturn(new PolicyCompanyFallbackClient.Result(List.of(profile), List.of()));

        PolicyThemeCollection result = target.listThemesWithStatus();

        PolicyCompanyCandidate company = result.themes().stream().filter(theme -> theme.themeCode().equals("DIGITAL_INFRA"))
                .findFirst().orElseThrow().companyPool().get(0);
        assertThat(company.symbol()).isEqualTo("600001");
        assertThat(company.latestPrice()).isNull();
        assertThat(company.financialQualityScore()).isNull();
        assertThat(company.dataGaps()).noneMatch(gap -> gap.contains("质量不足"));
        assertThat(company.financialQualityLabel()).contains("待核验");
        assertThat(company.leadershipRationale()).anyMatch(reason -> reason.contains("新浪") && reason.contains("https://"));
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("备用样本"));
        ArgumentCaptor<List<List<String>>> groups = ArgumentCaptor.forClass(List.class);
        verify(fallback).fetch(groups.capture());
        assertThat(groups.getValue()).hasSize(1);
        assertThat(groups.getValue().get(0)).contains("计算机").doesNotContain("银行");
        verifyNoInteractions(eastMoneyClient);
    }

    @Test
    void neverCallsFallbackWhileTheExistingCompanySourceWorks() {
        PolicyCompanyFallbackClient fallback = mock(PolicyCompanyFallbackClient.class);
        PolicyThemeService target = new PolicyThemeService(govPolicyClient, companyService, eastMoneyClient, CLOCK, fallback);
        stubPolicies(List.of(policy("数字基础设施算力建设规划", TODAY.toString())));
        when(companyService.listCompanies()).thenReturn(List.of(
                profile("600001", "设备样本", "计算机设备", "DIGITAL_INFRA", "80", "2000000000", "0.18", "1", "0.1")));
        target.listThemesWithStatus();
        verifyNoInteractions(fallback);
    }

    @Test
    void doesNotFillUnmatchedThemesWithUnrelatedPoliciesOrBaselineScores() {
        stubPolicies(List.of(
                policy("关于加强暑期旅游安全管理的通知", TODAY.toString())));
        when(companyService.listCompanies()).thenReturn(List.of());

        assertThat(service.listThemes()).hasSize(3).allSatisfy(theme -> {
            assertThat(theme.strengthScore()).isZero();
            assertThat(theme.signals()).isEmpty();
            assertThat(theme.policyLevel()).isEqualTo("政策线索");
            assertThat(theme.timeHorizon()).isEqualTo("2–12周观察");
        });
    }

    @Test
    void doesNotTurnFuturePoliciesIntoCurrentSignals() {
        stubPolicies(List.of(
                policy("关于推进算力基础设施建设的通知", "2099-01-01")));
        when(companyService.listCompanies()).thenReturn(List.of());

        assertThat(theme(service, "DIGITAL_INFRA").signals()).isEmpty();
        assertThat(theme(service, "DIGITAL_INFRA").strengthScore()).isZero();
    }

    @Test
    void normalizesRepostedTitlesWithoutInflatingEvidenceStrengthOrClaimingAgentVerification() {
        String date = TODAY.minusDays(2).toString();
        GovPolicyItem original = policy("关于推进算力基础设施建设的通知", date);
        GovPolicyItem repost = new GovPolicyItem("转载来源", "html", "  关于推进 算力基础设施建设的通知。 ",
                "https://repost.example/item", date, 80);
        when(companyService.listCompanies()).thenReturn(List.of());
        stubPolicies(List.of(original));
        PolicyTheme single = theme(service, "DIGITAL_INFRA");
        stubPolicies(List.of(original, repost));

        PolicyTheme deduplicated = theme(service, "DIGITAL_INFRA");

        assertThat(deduplicated.strengthScore()).isEqualByComparingTo(single.strengthScore());
        assertThat(deduplicated.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.url()).isEqualTo(original.url());
            assertThat(signal.signalType()).contains("标题关键词匹配", "来源权重", "90天内发布");
            assertThat(signal.signalType()).doesNotContain("Agent", "原文", "确认");
        });
    }

    @Test
    void keepsPolicyFetchFailureAsAnExplicitEmptyState() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(40)).thenThrow(new IllegalStateException("政策源超时"));
        when(companyService.listCompanies()).thenReturn(List.of());

        assertThat(service.listThemes()).hasSize(3).allSatisfy(theme -> {
            assertThat(theme.signals()).isEmpty();
            assertThat(theme.strengthScore()).isZero();
            assertThat(theme.risks()).contains("政策源未取得有效数据");
        });
    }

    @Test
    void addsOnlyMatchedSectorThemesAndUsesIndustryInsteadOfCompanyNamesWithoutExtraFinanceRequests() {
        stubPolicies(List.of(
                policy("关于促进创新药产业发展的意见", TODAY.toString())));
        when(companyService.listCompanies()).thenReturn(List.of(
                profileWithoutFinance("600001", "真实行业样本", "化学制药", "NEW_QUALITY_PRODUCTIVITY", "48", "5000000000"),
                profileWithoutFinance("600002", "创新药热门概念", "银行", "DIGITAL_INFRA", "90", "8000000000")
        ));

        List<PolicyTheme> themes = service.listThemes();
        assertThat(themes).extracting(PolicyTheme::themeCode)
                .containsExactlyInAnyOrder("NEW_QUALITY_PRODUCTIVITY", "DIGITAL_INFRA", "GREEN_TRANSITION", "INNOVATIVE_MEDICINE");
        PolicyTheme medicine = themes.stream().filter(row -> row.themeCode().equals("INNOVATIVE_MEDICINE")).findFirst().orElseThrow();
        assertThat(medicine.companyPool()).singleElement().satisfies(candidate -> {
            assertThat(candidate.symbol()).isEqualTo("600001");
            assertThat(candidate.researchRole()).isEqualTo("行业关键词相关，待主营核验");
            assertThat(candidate.dataGaps()).contains("最近年度财报指标未匹配，需要读取年报");
        });
        verifyNoInteractions(eastMoneyClient);
    }

    @Test
    void prioritizesRecentEvidenceAndUsesAnInclusiveNinetyDayWindowWithoutFutureIntradaySignals() {
        stubPolicies(List.of(
                policy("算力建设旧规划", TODAY.minusDays(91).toString()),
                policy("算力建设日期不明", null),
                policy("算力建设九十日方案", TODAY.minusDays(90).toString()),
                policy("算力建设近期方案", TODAY.minusDays(7).toString()),
                policy("算力建设未来时刻一", "2026-10-17T04:00:01Z"),
                policy("算力建设未来时刻二", "2026-10-17 12:00:01"),
                policy("算力建设未来日期", TODAY.plusDays(1).toString())
        ));
        when(companyService.listCompanies()).thenReturn(List.of());

        List<PolicySignal> signals = theme(service, "DIGITAL_INFRA").signals();

        assertThat(signals).extracting(PolicySignal::summary).containsExactly(
                "算力建设近期方案", "算力建设九十日方案", "算力建设旧规划", "算力建设日期不明");
        assertThat(signals.get(0).signalType()).contains("90天内发布");
        assertThat(signals.get(1).signalType()).contains("90天内发布");
        assertThat(signals.get(2).signalType()).contains("90天前发布");
        assertThat(signals.get(3).signalType()).contains("发布时间待核验").doesNotContain("90天内发布");
        assertThat(signals.get(0).confidence()).isGreaterThan(signals.get(1).confidence());
        assertThat(signals.get(1).confidence()).isGreaterThan(signals.get(2).confidence());
    }

    @Test
    void aNewerRepostDoesNotResetThePolicyPublicationAge() {
        GovPolicyItem original = policy("算力建设长期规划", TODAY.minusDays(100).toString());
        GovPolicyItem repost = new GovPolicyItem("转载源", "html", "算力建设 长期规划。",
                "https://repost.example/old-policy", TODAY.toString(), 100);
        stubPolicies(List.of(repost, original));
        when(companyService.listCompanies()).thenReturn(List.of());

        assertThat(theme(service, "DIGITAL_INFRA").signals()).singleElement().satisfies(signal -> {
            assertThat(signal.publishedAt()).isEqualTo(original.publishedAt());
            assertThat(signal.url()).isEqualTo(original.url());
            assertThat(signal.signalType()).contains("90天前发布");
        });
    }

    @Test
    void coversExplicitSectorTopicsWithoutTreatingAgricultureAsEnergyOrGenericIndustryAsManufacturing() {
        when(companyService.listCompanies()).thenReturn(List.of());
        stubPolicies(List.of(
                policy("促进消费品以旧换新的通知", TODAY.toString()),
                policy("集成电路产业发展意见", TODAY.toString()),
                policy("生物医药发展意见", TODAY.toString()),
                policy("交通运输发展规划", TODAY.toString()),
                policy("农业农村发展通知", TODAY.toString()),
                policy("城市更新实施方案", TODAY.toString()),
                policy("资本市场改革意见", TODAY.toString()),
                policy("关键矿产开发规划", TODAY.toString()),
                policy("商业航天产业规划", TODAY.toString())
        ));

        List<PolicyTheme> themes = service.listThemes();

        assertThat(themes).extracting(PolicyTheme::themeCode).containsExactlyInAnyOrder(
                "NEW_QUALITY_PRODUCTIVITY", "DIGITAL_INFRA", "GREEN_TRANSITION", "CONSUMPTION", "SEMICONDUCTOR",
                "INNOVATIVE_MEDICINE", "AUTO_TRANSPORT", "AGRICULTURE_FOOD", "URBAN_RENEWAL", "FINANCE_CAPITAL",
                "RESOURCE_METALS", "DEFENSE_AEROSPACE");
        assertThat(themes.stream().filter(row -> row.themeCode().equals("GREEN_TRANSITION")
                || row.themeCode().equals("NEW_QUALITY_PRODUCTIVITY"))).allSatisfy(row -> {
            assertThat(row.signals()).isEmpty();
            assertThat(row.strengthScore()).isZero();
        });
    }

    private GovPolicyItem policy(String title, String date) {
        return new GovPolicyItem("官方来源", "json", title, "https://policy.example/" + title.hashCode(), date, 90);
    }

    private void stubPolicies(List<GovPolicyItem> policies) {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(40)).thenReturn(new GovPolicyFetchResult(policies, List.of()));
    }

    private PolicyTheme theme(PolicyThemeService target, String code) {
        return target.listThemes().stream().filter(row -> code.equals(row.themeCode())).findFirst().orElseThrow();
    }

    @Test
    void buildsAResearchOnlyLeaderCompanyPoolForEachPolicyTheme() {
        stubPolicies(List.of(
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
        assertThat(theme.companyPool().get(0).chainSegment()).isEqualTo("产业链环节待核验");
        assertThat(theme.companyPool().get(0).researchRole()).doesNotContain("龙头", "受益");
        assertThat(theme.companyPool().get(0).leadershipRationale()).anyMatch(reason -> reason.contains("主营") && reason.contains("订单"));
        assertThat(theme.companyPool().get(2).dataGaps()).contains("最近年度 ROE 或现金流质量不足，需要继续核验");
    }

    @Test
    void enrichesFastQuoteCandidatesWithAnnualFinancialsBeforeRanking() {
        stubPolicies(List.of(
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
