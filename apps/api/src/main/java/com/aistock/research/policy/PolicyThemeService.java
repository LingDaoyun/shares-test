package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PolicyThemeService {

    private final GovPolicyClient govPolicyClient;
    private final CompanyService companyService;
    private final EastMoneyClient eastMoneyClient;

    public PolicyThemeService(
            GovPolicyClient govPolicyClient,
            CompanyService companyService,
            EastMoneyClient eastMoneyClient
    ) {
        this.govPolicyClient = govPolicyClient;
        this.companyService = companyService;
        this.eastMoneyClient = eastMoneyClient;
    }

    public List<PolicyTheme> listThemes() {
        List<GovPolicyItem> policies = fetchPoliciesSafely();
        List<CompanyProfile> companyProfiles = fetchCompaniesSafely();
        List<PolicyTheme> themes = List.of(
                new PolicyTheme(
                        "NEW_QUALITY_PRODUCTIVITY",
                        "新质生产力与高端制造",
                        "国家级",
                        "2026-2030",
                        scoreFor(policies, List.of("现代化", "科技", "制造", "产业", "设备", "应急", "安全")),
                        List.of("工业母机", "机器人核心零部件", "工业软件", "高端传感器"),
                        signalsFor(policies, "长期规划", List.of("现代化", "科技", "制造", "产业", "设备", "应急", "安全")),
                        List.of("概念拥挤", "订单兑现慢", "设备周期波动"),
                        companyPool(companyProfiles, "NEW_QUALITY_PRODUCTIVITY",
                                List.of("高端装备", "工业软件", "核心零部件", "智能制造"))
                ),
                new PolicyTheme(
                        "DIGITAL_INFRA",
                        "数字经济与算力基础设施",
                        "部委/地方共振",
                        "2025-2030",
                        scoreFor(policies, List.of("数字", "数据", "网络", "信息", "智能", "算力", "人工智能")),
                        List.of("算力中心", "液冷", "光模块", "数据安全", "行业应用软件"),
                        signalsFor(policies, "专项工程", List.of("数字", "数据", "网络", "信息", "智能", "算力", "人工智能")),
                        List.of("估值波动大", "技术迭代快", "海外限制"),
                        companyPool(companyProfiles, "DIGITAL_INFRA",
                                List.of("算力基础设施", "数据安全", "行业软件", "通信网络"))
                ),
                new PolicyTheme(
                        "GREEN_TRANSITION",
                        "绿色低碳与能源转型",
                        "国家级",
                        "长期",
                        scoreFor(policies, List.of("绿色", "低碳", "能源", "生态", "环保", "农业", "农村")),
                        List.of("储能", "电网设备", "新能源运营", "节能改造", "循环经济"),
                        signalsFor(policies, "政策兑现", List.of("绿色", "低碳", "能源", "生态", "环保", "农业", "农村")),
                        List.of("产能过剩", "价格战", "补贴退坡"),
                        companyPool(companyProfiles, "GREEN_TRANSITION",
                                List.of("电网设备", "储能", "新能源运营", "节能环保"))
                )
        );
        return themes.stream()
                .sorted(Comparator.comparing(PolicyTheme::strengthScore).reversed())
                .toList();
    }

    public Map<String, PolicyTheme> themeIndex() {
        return listThemes().stream().collect(java.util.stream.Collectors.toMap(PolicyTheme::themeCode, theme -> theme));
    }

    private List<GovPolicyItem> fetchPoliciesSafely() {
        try {
            return govPolicyClient.fetchLatestPolicies(40);
        } catch (IllegalStateException exception) {
            return List.of(new GovPolicyItem(
                    "系统降级",
                    "fallback",
                    "中国政府网最新政策数据暂时不可用",
                    "https://www.gov.cn/zhengce/zuixin/",
                    null,
                    20
            ));
        }
    }

    private List<CompanyProfile> fetchCompaniesSafely() {
        try {
            return companyService.listCompanies();
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    private List<PolicyCompanyCandidate> companyPool(
            List<CompanyProfile> companies,
            String themeCode,
            List<String> chainSegments
    ) {
        return companies.stream()
                .filter(company -> themeCode.equals(company.themeCode()))
                .filter(company -> company.name() != null && !company.name().contains("ST"))
                .sorted(Comparator.comparing(this::companyPoolScore).reversed())
                .limit(12)
                .map(company -> toCompanyCandidate(company, chainSegments))
                .sorted(Comparator.comparing(PolicyCompanyCandidate::financialQualityScore).reversed()
                        .thenComparing(candidate -> candidate.amount() == null ? BigDecimal.ZERO : candidate.amount(), Comparator.reverseOrder()))
                .limit(5)
                .toList();
    }

    private PolicyCompanyCandidate toCompanyCandidate(CompanyProfile company, List<String> chainSegments) {
        EastMoneyAnnualIndicator annualIndicator = annualIndicatorSafely(company);
        BigDecimal financialScore = financialQualityScore(company, annualIndicator);
        List<String> dataGaps = companyDataGaps(company, annualIndicator);
        return new PolicyCompanyCandidate(
                company.symbol(),
                company.name(),
                company.industry(),
                inferChainSegment(company, chainSegments),
                "产业龙头研究候选",
                leadershipRationale(company),
                financialScore,
                financialQualityLabel(financialScore),
                company.latestPrice(),
                company.peTtm(),
                company.pbRatio(),
                company.amount(),
                "不荐股",
                dataGaps
        );
    }

    private BigDecimal companyPoolScore(CompanyProfile company) {
        return factor(company, "policy_theme_relevance").multiply(BigDecimal.valueOf(35))
                .add(financialQualityScore(company, null).multiply(new BigDecimal("0.35")))
                .add(amountScore(company).multiply(new BigDecimal("0.20")))
                .add(valuationContextScore(company).multiply(new BigDecimal("0.10")));
    }

    private EastMoneyAnnualIndicator annualIndicatorSafely(CompanyProfile company) {
        if (hasProfileFinancials(company)) {
            return null;
        }
        try {
            return eastMoneyClient.fetchAnnualIndicatorHistory(company.symbol(), 1).stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private boolean hasProfileFinancials(CompanyProfile company) {
        return company.financialReportDate() != null && !company.financialReportDate().isBlank()
                && company.factors() != null
                && company.factors().containsKey("roe_annual")
                && company.factors().containsKey("operating_cash_flow_per_share");
    }

    private BigDecimal financialQualityScore(CompanyProfile company, EastMoneyAnnualIndicator annualIndicator) {
        BigDecimal score = BigDecimal.valueOf(45);
        BigDecimal roe = annualIndicator == null ? factor(company, "roe_annual") : nullToZero(annualIndicator.roe());
        BigDecimal cashFlow = annualIndicator == null
                ? factor(company, "operating_cash_flow_per_share")
                : nullToZero(annualIndicator.operatingCashFlowPerShare());
        BigDecimal profitGrowth = annualIndicator == null
                ? factor(company, "net_profit_growth")
                : nullToZero(annualIndicator.netProfitGrowth());
        BigDecimal revenueGrowth = annualIndicator == null
                ? factor(company, "revenue_growth")
                : nullToZero(annualIndicator.revenueGrowth());
        if (roe.compareTo(new BigDecimal("0.15")) >= 0) {
            score = score.add(BigDecimal.valueOf(26));
        } else if (roe.compareTo(new BigDecimal("0.08")) >= 0) {
            score = score.add(BigDecimal.valueOf(16));
        } else if (roe.compareTo(BigDecimal.ZERO) < 0) {
            score = score.subtract(BigDecimal.valueOf(18));
        }
        if (cashFlow.compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(BigDecimal.valueOf(14));
        } else if (cashFlow.compareTo(BigDecimal.ZERO) < 0) {
            score = score.subtract(BigDecimal.valueOf(12));
        }
        if (profitGrowth.compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(BigDecimal.valueOf(8));
        }
        if (revenueGrowth.compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(BigDecimal.valueOf(7));
        }
        return score.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal amountScore(CompanyProfile company) {
        BigDecimal amount = company.amount() == null ? BigDecimal.ZERO : company.amount();
        if (amount.compareTo(new BigDecimal("5000000000")) >= 0) {
            return BigDecimal.valueOf(100);
        }
        if (amount.compareTo(new BigDecimal("1000000000")) >= 0) {
            return BigDecimal.valueOf(78);
        }
        if (amount.compareTo(new BigDecimal("300000000")) >= 0) {
            return BigDecimal.valueOf(60);
        }
        return BigDecimal.valueOf(42);
    }

    private BigDecimal valuationContextScore(CompanyProfile company) {
        BigDecimal pe = company.peTtm();
        BigDecimal pb = company.pbRatio();
        BigDecimal score = BigDecimal.valueOf(60);
        if (pe != null && pe.compareTo(BigDecimal.ZERO) > 0 && pe.compareTo(new BigDecimal("45")) <= 0) {
            score = score.add(BigDecimal.valueOf(18));
        }
        if (pb != null && pb.compareTo(BigDecimal.ZERO) > 0 && pb.compareTo(new BigDecimal("5")) <= 0) {
            score = score.add(BigDecimal.valueOf(12));
        }
        return score.min(BigDecimal.valueOf(90));
    }

    private String financialQualityLabel(BigDecimal score) {
        if (score.compareTo(new BigDecimal("80")) >= 0) {
            return "财报质量较好";
        }
        if (score.compareTo(new BigDecimal("65")) >= 0) {
            return "财报质量可跟踪";
        }
        return "财报质量待核验";
    }

    private List<String> leadershipRationale(CompanyProfile company) {
        List<String> rationale = new ArrayList<>();
        rationale.add("政策主题匹配度 " + company.themeRelevance());
        if (company.amount() != null && company.amount().compareTo(new BigDecimal("1000000000")) >= 0) {
            rationale.add("成交额靠前，具备持续跟踪流动性");
        }
        if (factor(company, "roe_annual").compareTo(new BigDecimal("0.08")) >= 0) {
            rationale.add("最近年度 ROE 为正且达到观察阈值");
        }
        if (factor(company, "operating_cash_flow_per_share").compareTo(BigDecimal.ZERO) > 0) {
            rationale.add("经营现金流为正，财报质量更易复核");
        }
        if (rationale.size() == 1) {
            rationale.add("行业位置和主营占比仍需公告复核");
        }
        return rationale;
    }

    private List<String> companyDataGaps(CompanyProfile company, EastMoneyAnnualIndicator annualIndicator) {
        List<String> gaps = new ArrayList<>();
        boolean hasAnnual = annualIndicator != null
                || (company.financialReportDate() != null && !company.financialReportDate().isBlank());
        BigDecimal roe = annualIndicator == null ? factor(company, "roe_annual") : nullToZero(annualIndicator.roe());
        BigDecimal cashFlow = annualIndicator == null
                ? factor(company, "operating_cash_flow_per_share")
                : nullToZero(annualIndicator.operatingCashFlowPerShare());
        if (!hasAnnual) {
            gaps.add("最近年度财报指标未匹配，需要读取年报");
        }
        if (roe.compareTo(new BigDecimal("0.08")) < 0
                || cashFlow.compareTo(BigDecimal.ZERO) <= 0) {
            gaps.add("最近年度 ROE 或现金流质量不足，需要继续核验");
        }
        gaps.add("仍需核验主营收入中该产业方向占比");
        return gaps;
    }

    private String inferChainSegment(CompanyProfile company, List<String> chainSegments) {
        String text = (company.name() + " " + company.industry()).toLowerCase();
        for (String segment : chainSegments) {
            if (text.contains(segment.toLowerCase())) {
                return segment;
            }
        }
        return chainSegments.isEmpty() ? "产业链位置待核验" : chainSegments.get(0);
    }

    private BigDecimal factor(CompanyProfile company, String key) {
        return company.factors() == null ? BigDecimal.ZERO : company.factors().getOrDefault(key, BigDecimal.ZERO);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scoreFor(List<GovPolicyItem> policies, List<String> keywords) {
        List<GovPolicyItem> matched = policies.stream()
                .filter(policy -> containsAny(policy.title(), keywords))
                .toList();
        if (matched.isEmpty()) {
            return new BigDecimal("58.00");
        }

        Set<String> sources = matched.stream().map(GovPolicyItem::source).collect(Collectors.toSet());
        BigDecimal sourceReliability = matched.stream()
                .map(policy -> BigDecimal.valueOf(policy.sourceWeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(matched.size()), 2, RoundingMode.HALF_UP);
        BigDecimal crossSourceBonus = BigDecimal.valueOf(Math.min(sources.size() * 4L, 16));
        BigDecimal hitBonus = BigDecimal.valueOf(Math.min(matched.size() * 3L, 18));
        BigDecimal recencyBonus = BigDecimal.valueOf(Math.min(matched.stream().filter(this::isRecent).count() * 2L, 8));
        BigDecimal score = BigDecimal.valueOf(42)
                .add(sourceReliability.multiply(new BigDecimal("0.28")))
                .add(crossSourceBonus)
                .add(hitBonus)
                .add(recencyBonus);
        return score.min(new BigDecimal("96")).setScale(2, RoundingMode.HALF_UP);
    }

    private List<PolicySignal> signalsFor(List<GovPolicyItem> policies, String signalType, List<String> keywords) {
        List<PolicySignal> matched = policies.stream()
                .filter(policy -> containsAny(policy.title(), keywords))
                .sorted(Comparator.comparing((GovPolicyItem policy) -> signalConfidence(policy, keywords)).reversed())
                .limit(6)
                .map(policy -> new PolicySignal(
                        policy.source(),
                        signalType + " / " + agentVerdict(policy, keywords),
                        policy.title(),
                        signalConfidence(policy, keywords),
                        policy.url(),
                        policy.publishedAt()
                ))
                .toList();
        if (!matched.isEmpty()) {
            return matched;
        }
        return policies.stream()
                .sorted(Comparator.comparing((GovPolicyItem policy) -> signalConfidence(policy, keywords)).reversed())
                .limit(4)
                .map(policy -> new PolicySignal(
                        policy.source(),
                        "最新政策 / 待交叉验证",
                        policy.title(),
                        Math.max(45, policy.sourceWeight() / 2),
                        policy.url(),
                        policy.publishedAt()
                ))
                .toList();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        return keywords.stream().anyMatch(text::contains);
    }

    private int signalConfidence(GovPolicyItem policy, List<String> keywords) {
        String title = policy.title() == null ? "" : policy.title();
        int keywordHits = (int) keywords.stream().filter(title::contains).count();
        int confidence = 35
                + Math.min(policy.sourceWeight() / 3, 30)
                + Math.min(keywordHits * 8, 24);
        if (isRecent(policy)) {
            confidence += 20;
        } else if (policy.publishedAt() == null || policy.publishedAt().isBlank()) {
            confidence -= 8;
        } else {
            confidence -= 12;
        }
        return Math.max(35, Math.min(confidence, 96));
    }

    private String agentVerdict(GovPolicyItem policy, List<String> keywords) {
        List<String> agents = new ArrayList<>();
        if (containsAny(policy.title(), keywords)) {
            agents.add("政策原文Agent命中");
        }
        if (policy.sourceWeight() >= 85) {
            agents.add("权威来源Agent确认");
        }
        if (isRecent(policy)) {
            agents.add("时效Agent确认");
        } else {
            agents.add("时效Agent待验证");
        }
        if (agents.isEmpty()) {
            return "待交叉验证";
        }
        return String.join("+", agents);
    }

    private boolean isRecent(GovPolicyItem policy) {
        if (policy.publishedAt() == null || policy.publishedAt().isBlank()) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(policy.publishedAt().substring(0, Math.min(10, policy.publishedAt().length())));
            return date.isAfter(LocalDate.now().minusYears(2));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException exception) {
            return false;
        }
    }
}
