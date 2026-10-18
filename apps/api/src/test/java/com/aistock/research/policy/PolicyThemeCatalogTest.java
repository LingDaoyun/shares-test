package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyThemeCatalogTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final List<String> NEW_CODES = List.of(
            "QUANTUM_TECHNOLOGY", "BIOMANUFACTURING", "HYDROGEN_ENERGY", "NUCLEAR_FUSION",
            "BRAIN_COMPUTER_INTERFACE", "EMBODIED_INTELLIGENCE", "SIX_G_COMMUNICATION", "ADVANCED_MATERIALS",
            "MARINE_ECONOMY", "SILVER_ECONOMY", "LOW_ALTITUDE_ECONOMY", "SODIUM_ION_BATTERY", "FLOW_BATTERY");
    private final GovPolicyClient policyClient = mock(GovPolicyClient.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final PolicyThemeService service = new PolicyThemeService(
            policyClient, companyService, mock(EastMoneyClient.class), CLOCK);

    @ParameterizedTest
    @CsvSource({
            "量子科技, QUANTUM_TECHNOLOGY", "量子计算, QUANTUM_TECHNOLOGY",
            "生物制造, BIOMANUFACTURING", "合成生物, BIOMANUFACTURING",
            "氢能, HYDROGEN_ENERGY", "绿色氢氨醇, HYDROGEN_ENERGY",
            "核聚变能, NUCLEAR_FUSION", "可控核聚变, NUCLEAR_FUSION",
            "脑机接口, BRAIN_COMPUTER_INTERFACE",
            "具身智能, EMBODIED_INTELLIGENCE", "人形机器人, EMBODIED_INTELLIGENCE",
            "第六代移动通信, SIX_G_COMMUNICATION", "6G, SIX_G_COMMUNICATION", "6g, SIX_G_COMMUNICATION",
            "新材料, ADVANCED_MATERIALS", "先进材料, ADVANCED_MATERIALS",
            "海洋经济, MARINE_ECONOMY", "海水淡化, MARINE_ECONOMY", "深海工程, MARINE_ECONOMY",
            "银发经济, SILVER_ECONOMY", "养老服务, SILVER_ECONOMY",
            "低空经济, LOW_ALTITUDE_ECONOMY", "eVTOL, LOW_ALTITUDE_ECONOMY",
            "钠电池, SODIUM_ION_BATTERY", "钠离子电池, SODIUM_ION_BATTERY",
            "液流电池, FLOW_BATTERY", "全钒液流, FLOW_BATTERY"
    })
    void identifiesExplicitPlanningTermsAsSeparateThemes(String term, String code) {
        stub("国家规划产业部署", "推进" + term + "发展。", List.of());

        assertThat(service.listThemes()).filteredOn(theme -> theme.themeCode().equals(code))
                .singleElement().satisfies(theme -> {
                    assertThat(theme.signals()).singleElement().satisfies(signal -> {
                        assertThat(signal.excerpt()).contains(term);
                        assertThat(signal.planningBaseline()).isTrue();
                    });
                    assertThat(theme.companyPool()).isEmpty();
                });
    }

    @Test
    void doesNotInferSpecialistThemesFromBroadIndustrialLanguage() {
        stub("产业发展部署", "推动制造、能源、医药、通信、消费、农村领域发展，完善普通锂电池回收体系。", List.of());

        assertThat(service.listThemes()).extracting(PolicyTheme::themeCode).doesNotContainAnyElementsOf(NEW_CODES);
    }

    @Test
    void culturalHeritageBackgroundDoesNotBecomeIndustryEvidence() {
        stub("文化遗产保护规划", "运用卫星遥感保护农村传统村落，记录传统医药和工业遗产制造历史，完善金融支持。", List.of());

        assertThat(service.listThemes()).allSatisfy(theme -> assertThat(theme.signals()).isEmpty());
    }

    @Test
    void consumerElectronicsInABatteryPlanDoesNotTriggerConsumptionStimulus() {
        stub("新型电池产业发展规划", "拓展消费电子领域应用，推动电池技术创新。", List.of());

        assertThat(service.listThemes()).extracting(PolicyTheme::themeCode).doesNotContain("CONSUMPTION");
    }

    @Test
    void broadCompanyIndustriesAndPromotionalNamesDoNotImplySpecialistBusinesses() {
        String directions = "量子科技、生物制造、氢能、核聚变能、脑机接口、具身智能、第六代移动通信、"
                + "新材料、海洋经济、银发经济、低空经济、钠电池、液流电池";
        stub("规划产业部署", "推动" + directions + "发展。", List.of(
                profile("600001", directions, "银行"), profile("600002", directions, "通信"),
                profile("600003", directions, "医药"), profile("600004", directions, "电池"),
                profile("600005", directions, "机械设备"), profile("600006", directions, "汽车整车")));

        List<PolicyTheme> specificThemes = service.listThemes().stream()
                .filter(theme -> NEW_CODES.contains(theme.themeCode())).toList();
        assertThat(specificThemes).extracting(PolicyTheme::themeCode).containsExactlyInAnyOrderElementsOf(NEW_CODES);
        assertThat(specificThemes).allSatisfy(theme -> assertThat(theme.companyPool()).isEmpty());
    }

    @Test
    void specificIndustryEvidenceProducesCandidatesWithoutMixingBatteryRoutes() {
        stub("产业部署", "推动钠离子电池、液流电池和低空经济发展。", List.of(
                profile("600001", "钠电样本", "钠离子电池"),
                profile("600002", "液流样本", "液流电池"),
                profile("600003", "低空样本", "通用航空"),
                profile("600004", "普通电池", "电池")));

        List<PolicyTheme> themes = service.listThemes();
        assertCandidate(themes, "SODIUM_ION_BATTERY", "600001");
        assertCandidate(themes, "FLOW_BATTERY", "600002");
        assertCandidate(themes, "LOW_ALTITUDE_ECONOMY", "600003");
    }

    @ParameterizedTest
    @CsvSource({
            "高端制造, NEW_QUALITY_PRODUCTIVITY", "算力, DIGITAL_INFRA", "光伏, GREEN_TRANSITION",
            "固态电池, SOLID_STATE_BATTERY", "提振消费, CONSUMPTION", "集成电路, SEMICONDUCTOR",
            "创新药, INNOVATIVE_MEDICINE", "交通运输, AUTO_TRANSPORT", "农业农村, AGRICULTURE_FOOD",
            "城市更新, URBAN_RENEWAL", "科技金融, FINANCE_CAPITAL", "金融工具, FINANCE_CAPITAL",
            "关键矿产, RESOURCE_METALS", "商业航天, DEFENSE_AEROSPACE", "卫星互联网, DEFENSE_AEROSPACE"
    })
    void preservesExistingThemeCodesForExplicitIndustryPolicies(String term, String code) {
        stub(term + "发展规划", "", List.of());

        assertThat(service.listThemes()).filteredOn(theme -> theme.themeCode().equals(code))
                .singleElement().satisfies(theme -> assertThat(theme.signals()).hasSize(1));
    }

    @ParameterizedTest
    @CsvSource({
            "前8月电力市场交易电量同比增长19.2%, GREEN_TRANSITION",
            "2026年8月份全国天然气运行快报, GREEN_TRANSITION",
            "《油气管网设施公平开放信息公开与报送办法》政策解读, GREEN_TRANSITION",
            "关于公开征求《供电企业信息公开实施办法》（修订稿）意见的通知, GREEN_TRANSITION",
            "国务院关于《医疗康复护理扩容提升工程实施方案》的批复, INNOVATIVE_MEDICINE",
            "疾病预防控制“十五五”规划, INNOVATIVE_MEDICINE",
            "国民健康“十五五”规划, INNOVATIVE_MEDICINE",
            "中医药振兴发展“十五五”规划, INNOVATIVE_MEDICINE",
            "2026年1-8月公路水路交通固定资产投资完成情况, AUTO_TRANSPORT",
            "旅游强国建设“十五五”规划, CULTURE_TOURISM",
            "体育强国建设“十五五”规划, SPORTS_INDUSTRY",
            "国务院关于印发《全民健身计划（2026—2030年）》的通知, SPORTS_INDUSTRY",
            "教育发展“十五五”规划, EDUCATION_TRAINING",
            "国务院关于《特殊教育发展提升“十五五”行动计划》的批复, EDUCATION_TRAINING",
            "美丽中国建设“十五五”规划, ECOLOGICAL_PROTECTION",
            "现代化应急体系建设“十五五”规划, EMERGENCY_SAFETY"
    })
    void classifiesObservedPreviouslyUnclassifiedTitles(String title, String code) {
        stub(title, "", List.of());

        assertThat(service.listThemes()).filteredOn(theme -> theme.themeCode().equals(code))
                .singleElement().satisfies(theme ->
                        assertThat(theme.signals()).singleElement().satisfies(signal ->
                                assertThat(signal.summary()).isEqualTo(title)));
    }

    @ParameterizedTest
    @CsvSource({
            "医疗康复, INNOVATIVE_MEDICINE", "护理, INNOVATIVE_MEDICINE",
            "公路, AUTO_TRANSPORT", "水路, AUTO_TRANSPORT",
            "职业教育, EDUCATION_TRAINING", "职业技能培训, EDUCATION_TRAINING"
    })
    void recognizesExplicitServiceAndTransportTerms(String term, String code) {
        stub("专项发展部署", "推进" + term + "发展。", List.of());

        assertThat(service.listThemes()).filteredOn(theme -> theme.themeCode().equals(code))
                .singleElement().satisfies(theme -> assertThat(theme.signals()).hasSize(1));
    }

    @Test
    void doesNotInferNewServiceIndustriesFromGenericCulturalAndSafetyBackground() {
        stub("社区活动安排", "加强文化教育，弘扬体育精神，增强环境意识，做好应急准备和人员运输。", List.of());

        assertThat(service.listThemes()).extracting(PolicyTheme::themeCode).doesNotContain(
                "CULTURE_TOURISM", "SPORTS_INDUSTRY", "EDUCATION_TRAINING", "ECOLOGICAL_PROTECTION",
                "EMERGENCY_SAFETY", "AUTO_TRANSPORT");
    }

    @Test
    void additionalPlanningThemesRequireSpecificCompanyIndustryEvidence() {
        stub("发展规划", "推进旅游强国、体育强国、教育发展、美丽中国和应急体系建设。", List.of(
                profile("600001", "文旅样本", "旅游景区"),
                profile("600002", "体育样本", "体育用品"),
                profile("600003", "教育样本", "职业培训"),
                profile("600004", "环保样本", "污水处理"),
                profile("600005", "应急样本", "消防设备"),
                profile("600006", "旅游体育教育环保应急概念", "银行"),
                profile("600007", "旅游体育教育环保应急概念", "机械设备"),
                profile("600008", "旅游体育教育环保应急概念", "建筑材料")));

        List<PolicyTheme> themes = service.listThemes();
        assertCandidate(themes, "CULTURE_TOURISM", "600001");
        assertCandidate(themes, "SPORTS_INDUSTRY", "600002");
        assertCandidate(themes, "EDUCATION_TRAINING", "600003");
        assertCandidate(themes, "ECOLOGICAL_PROTECTION", "600004");
        assertCandidate(themes, "EMERGENCY_SAFETY", "600005");
    }

    private void stub(String title, String body, List<CompanyProfile> companies) {
        when(policyClient.fetchResearchPoliciesWithStatus(anyInt())).thenReturn(new GovPolicyFetchResult(List.of(
                new GovPolicyItem("官方规划来源", "plan", title, "https://policy.example/plan", "2026-03-14", 95, body, true)), List.of()));
        when(companyService.listCompanies()).thenReturn(companies);
    }

    private CompanyProfile profile(String symbol, String name, String industry) {
        return new CompanyProfile(symbol, name, "上交所", industry, "UNCLASSIFIED", BigDecimal.ZERO,
                null, null, null, null, null, null, null, "测试行业源", null, null, null, false,
                List.of(), List.of(), Map.of(), List.of());
    }

    private void assertCandidate(List<PolicyTheme> themes, String code, String symbol) {
        assertThat(themes).filteredOn(theme -> theme.themeCode().equals(code)).singleElement().satisfies(theme -> {
            assertThat(theme.companyPool()).extracting(PolicyCompanyCandidate::symbol).containsExactly(symbol);
            assertThat(theme.risks()).anyMatch(risk -> risk.contains("主营") && risk.contains("核验"));
        });
    }
}
