package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PolicyThemeService {

    private static final ZoneId POLICY_ZONE = ZoneId.of("Asia/Shanghai");
    private final GovPolicyClient govPolicyClient;
    private final CompanyService companyService;
    private final EastMoneyClient eastMoneyClient;
    private final PolicyCompanyFallbackClient companyFallback;
    private final Clock clock;

    @Autowired
    public PolicyThemeService(
            GovPolicyClient govPolicyClient,
            CompanyService companyService,
            EastMoneyClient eastMoneyClient,
            PolicyCompanyFallbackClient companyFallback
    ) {
        this(govPolicyClient, companyService, eastMoneyClient, Clock.system(POLICY_ZONE), companyFallback);
    }

    PolicyThemeService(GovPolicyClient govPolicyClient, CompanyService companyService,
                       EastMoneyClient eastMoneyClient, Clock clock) {
        this(govPolicyClient, companyService, eastMoneyClient, clock, null);
    }

    public PolicyThemeService(GovPolicyClient govPolicyClient, CompanyService companyService, EastMoneyClient eastMoneyClient) {
        this(govPolicyClient, companyService, eastMoneyClient, Clock.system(POLICY_ZONE), null);
    }

    PolicyThemeService(GovPolicyClient govPolicyClient, CompanyService companyService,
                       EastMoneyClient eastMoneyClient, Clock clock, PolicyCompanyFallbackClient companyFallback) {
        this.govPolicyClient = govPolicyClient;
        this.companyService = companyService;
        this.eastMoneyClient = eastMoneyClient;
        this.companyFallback = companyFallback;
        this.clock = clock.withZone(POLICY_ZONE);
    }

    public List<PolicyTheme> listThemes() {
        return listThemesWithStatus().themes();
    }

    public PolicyThemeCollection listThemesWithStatus() {
        List<String> dataGaps = new ArrayList<>();
        List<GovPolicyItem> policies = fetchPoliciesSafely(dataGaps);
        List<CompanyProfile> companyProfiles = fetchCompaniesSafely(dataGaps, policies);
        List<PolicyTheme> themes = themeDefinitions().stream()
                .map(definition -> buildTheme(definition, policies, companyProfiles))
                .filter(theme -> isCoreTheme(theme.themeCode()) || !theme.signals().isEmpty())
                .sorted(Comparator.comparing(PolicyTheme::strengthScore).reversed())
                .toList();
        return new PolicyThemeCollection(themes, dataGaps.stream().distinct().toList());
    }

    private record ThemeDefinition(String code, String name, List<String> keywords,
                                   List<String> chainSegments, List<String> risks, List<String> industries) {
    }

    private List<ThemeDefinition> themeDefinitions() {
        return List.of(
                new ThemeDefinition("NEW_QUALITY_PRODUCTIVITY", "新质生产力与高端制造",
                        List.of("新质生产力", "制造", "机器人", "工业母机", "工业软件", "高端装备", "设备更新", "专精特新"),
                        List.of("工业母机", "机器人核心零部件", "工业软件", "高端传感器"),
                        List.of("概念拥挤", "订单兑现慢", "设备周期波动"),
                        List.of("机械", "设备", "高端装备", "机器人", "自动化", "仪器", "工业软件", "软件开发", "电子元件", "新材料", "光学", "家电")),
                new ThemeDefinition("DIGITAL_INFRA", "数字经济与算力基础设施",
                        List.of("数字经济", "数字基础设施", "数字化", "数据要素", "算力", "人工智能", "数据中心", "通信网络", "数据安全", "工业互联网"),
                        List.of("算力中心", "液冷", "光模块", "数据安全", "行业应用软件"),
                        List.of("估值波动大", "技术迭代快", "海外限制"),
                        List.of("软件", "通信", "计算机", "互联网", "数据", "信息技术", "电子信息", "网络", "光模块", "电子元件", "半导体")),
                new ThemeDefinition("GREEN_TRANSITION", "绿色低碳与能源转型",
                        List.of("低碳", "能源", "电网", "储能", "电池", "节能", "循环经济", "碳中和", "光伏", "风电"),
                        List.of("储能", "电网设备", "新能源运营", "节能改造", "循环经济"),
                        List.of("产能过剩", "价格战", "补贴退坡"),
                        List.of("电力", "电池", "光伏", "风电", "新能源", "环保", "储能", "电网", "电源", "节能")),
                new ThemeDefinition("SOLID_STATE_BATTERY", "固态电池",
                        List.of("固态电池", "固态动力电池", "固体电解质", "固态电解质"),
                        List.of("固态电解质", "正负极材料", "电池制造设备", "电芯与系统集成"),
                        List.of("技术路线和量产良率待验证", "成本与规模化应用进度不确定", "电池行业候选不代表已具备固态电池业务，需核验公告及主营"),
                        List.of("电池", "电解质", "电池材料", "锂电", "储能")),
                new ThemeDefinition("CONSUMPTION", "消费与以旧换新",
                        List.of("消费", "以旧换新", "家电", "商贸"),
                        List.of("家电", "零售", "消费服务"), List.of("补贴落地节奏", "终端需求兑现"),
                        List.of("家电", "白色家电", "零售", "商业百货", "旅游酒店", "酒店旅游", "家用轻工", "纺织服装")),
                new ThemeDefinition("SEMICONDUCTOR", "半导体与国产替代",
                        List.of("半导体", "集成电路", "芯片", "光刻", "先进封装"),
                        List.of("芯片设计", "半导体设备", "半导体材料", "封装测试"), List.of("技术验证周期", "出口限制", "资本开支波动"),
                        List.of("半导体", "集成电路", "芯片", "电子化学品")),
                new ThemeDefinition("INNOVATIVE_MEDICINE", "创新药与医疗",
                        List.of("创新药", "生物医药", "医疗器械", "医保", "药品", "医疗卫生", "医药"),
                        List.of("创新药", "医疗器械", "医疗服务"), List.of("临床及审批不确定性", "医保支付及集采压力"),
                        List.of("化学制药", "生物制品", "生物制药", "中药", "医疗器械", "医疗服务", "医药商业")),
                new ThemeDefinition("AUTO_TRANSPORT", "汽车与交通运输",
                        List.of("汽车", "智能网联", "交通运输", "低空经济", "航空运输", "轨道交通", "充电基础设施", "车路云"),
                        List.of("汽车整车", "汽车零部件", "轨道交通", "物流"), List.of("需求与价格竞争", "基础设施建设节奏"),
                        List.of("汽车", "交运设备", "运输设备", "航空机场", "铁路公路", "航运港口", "交通运输", "物流")),
                new ThemeDefinition("AGRICULTURE_FOOD", "农业与食品",
                        List.of("农业", "农村", "粮食", "种业", "农机", "农产品", "食品安全", "畜牧", "渔业"),
                        List.of("种业", "种植", "养殖", "食品加工"), List.of("农产品价格周期", "天气与生物安全风险"),
                        List.of("农牧饲渔", "农林牧渔", "种植", "养殖", "种业", "农业", "食品", "农产品加工")),
                new ThemeDefinition("URBAN_RENEWAL", "房地产与城市更新",
                        List.of("房地产", "住房", "城市更新", "城中村", "保障房", "保障性住房", "市政", "建筑业", "城市基础设施"),
                        List.of("城市更新", "工程建设", "建筑材料", "物业服务"), List.of("项目资金到位节奏", "销售与回款风险"),
                        List.of("房地产", "工程建设", "建筑装饰", "建筑材料", "建筑建材", "水泥建材")),
                new ThemeDefinition("FINANCE_CAPITAL", "金融与资本市场",
                        List.of("金融", "资本市场", "证券", "保险", "银行", "上市公司监管", "股票回购", "并购重组"),
                        List.of("银行", "证券", "保险"), List.of("政策传导与信用风险", "市场交易活跃度波动"),
                        List.of("银行", "证券", "保险", "多元金融", "金融行业")),
                new ThemeDefinition("RESOURCE_METALS", "有色金属与矿产资源",
                        List.of("有色金属", "稀土", "关键矿产", "矿产资源", "战略矿产", "铜产业", "铝产业", "锂资源", "稀有金属", "黄金产业"),
                        List.of("矿产开发", "金属冶炼", "资源回收"), List.of("商品价格波动", "矿权与环保约束"),
                        List.of("有色金属", "小金属", "工业金属", "能源金属", "贵金属", "有色冶炼", "采掘")),
                new ThemeDefinition("DEFENSE_AEROSPACE", "国防与航天",
                        List.of("国防", "军工", "航天", "卫星", "航空装备", "航空发动机"),
                        List.of("航空装备", "航天装备", "卫星应用"), List.of("采购及验收节奏", "项目披露有限"),
                        List.of("航天航空", "军工", "航空装备", "飞机制造", "航天装备", "卫星"))
        );
    }

    private boolean isCoreTheme(String code) {
        return Set.of("NEW_QUALITY_PRODUCTIVITY", "DIGITAL_INFRA", "GREEN_TRANSITION").contains(code);
    }

    private PolicyTheme buildTheme(ThemeDefinition definition, List<GovPolicyItem> policies,
                                   List<CompanyProfile> companies) {
        List<PolicySignal> signals = signalsFor(policies, definition.keywords());
        List<String> risks = new ArrayList<>(definition.risks());
        risks.add(signals.stream().anyMatch(signal -> signal.excerpt() != null && !signal.excerpt().isBlank())
                ? "依据已采集的标题及正文片段匹配，需结合完整文件核验适用范围与实施条件；样本不代表完整政策覆盖"
                : "仅依据本次采集的政策标题线索，尚未核验全文条款；样本不代表完整90天政策覆盖");
        if (signals.stream().anyMatch(PolicySignal::planningBaseline)) risks.add("长期规划是趋势背景，来源发布日期不变，不代表近期新增催化或已兑现订单");
        if (policies.isEmpty()) risks.add("政策源未取得有效数据");
        if (signals.isEmpty()) risks.add("当前采集样本未匹配到本主题政策线索");
        List<PolicyCompanyCandidate> pool = signals.isEmpty() ? List.of()
                : isCoreTheme(definition.code()) ? companyPool(companies, definition.industries(), definition.chainSegments())
                : industryCompanyPool(companies, definition.industries(), definition.chainSegments());
        return new PolicyTheme(definition.code(), definition.name(), "政策线索", "2–12周观察",
                scoreFor(policies, definition.keywords()), definition.chainSegments(), signals, List.copyOf(risks), pool);
    }

    public Map<String, PolicyTheme> themeIndex() {
        return listThemes().stream().collect(java.util.stream.Collectors.toMap(PolicyTheme::themeCode, theme -> theme));
    }

    private List<GovPolicyItem> fetchPoliciesSafely(List<String> dataGaps) {
        try {
            GovPolicyFetchResult result = govPolicyClient.fetchLatestPoliciesWithStatus(40);
            for (String failure : result.failedSources()) {
                // GovPolicyClient joins the configured source name and root exception with this separator.
                // Keep only that name; never publish raw upstream response bodies, URLs or credentials.
                int separator = failure == null ? -1 : failure.indexOf('：');
                String name = separator > 0 ? failure.substring(0, separator).trim() : "";
                if (name.length() > 80 || name.contains(":") || name.contains("/") || name.contains("\n") || name.contains("\r")) name = "";
                dataGaps.add(name.isBlank() ? "部分政策源采集失败，本次覆盖不完整。"
                        : name + "：政策源采集失败，本次覆盖不完整。");
            }
            List<GovPolicyItem> policies = deduplicatePolicies(result.items());
            if (policies.isEmpty()) dataGaps.add("政策源未取得有效数据，本次没有可用政策线索。");
            return policies;
        } catch (RuntimeException exception) {
            dataGaps.add("政策源采集失败，本次没有可用政策线索。");
            return List.of();
        }
    }

    private List<CompanyProfile> fetchCompaniesSafely(List<String> dataGaps, List<GovPolicyItem> policies) {
        try {
            List<CompanyProfile> companies = companyService.listCompanies();
            if (!companies.isEmpty()) return companies;
        } catch (RuntimeException exception) {
            // Collection status contains fixed messages only; details remain outside public snapshots.
        }
        if (companyFallback != null) {
            List<List<String>> groups = themeDefinitions().stream()
                    .filter(definition -> !signalsFor(policies, definition.keywords()).isEmpty())
                    .map(ThemeDefinition::industries).toList();
            PolicyCompanyFallbackClient.Result fallback = companyFallback.fetch(groups);
            dataGaps.addAll(fallback.dataGaps());
            if (!fallback.companies().isEmpty()) {
                dataGaps.add("实时公司源不可用，本轮使用新浪行业分类备用样本；行业与主营待核验，行情和财务暂缺。");
                return fallback.companies();
            }
        }
        dataGaps.add("公司样本未取得有效数据，上市公司关联暂不可用。");
        return List.of();
    }

    private List<PolicyCompanyCandidate> companyPool(
            List<CompanyProfile> companies,
            List<String> industries,
            List<String> chainSegments
    ) {
        return companies.stream()
                .filter(company -> containsAny(company.industry(), industries))
                .filter(company -> company.name() != null && !company.name().contains("ST"))
                .sorted(Comparator.comparing(this::companyPoolScore).reversed())
                .limit(12)
                .map(company -> toCompanyCandidate(company, chainSegments, true, true))
                .sorted(Comparator.comparing(PolicyCompanyCandidate::financialQualityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(candidate -> candidate.amount() == null ? BigDecimal.ZERO : candidate.amount(), Comparator.reverseOrder()))
                .limit(5)
                .toList();
    }

    private List<PolicyCompanyCandidate> industryCompanyPool(List<CompanyProfile> companies,
                                                            List<String> industries, List<String> chainSegments) {
        return companies.stream()
                .filter(company -> company.name() != null && !company.name().contains("ST"))
                .filter(company -> containsAny(company.industry(), industries))
                .sorted(Comparator.comparing((CompanyProfile company) -> financialQualityScore(company, null)).reversed()
                        .thenComparing(company -> nullToZero(company.amount()), Comparator.reverseOrder()))
                .limit(5)
                .map(company -> toCompanyCandidate(company, chainSegments, false, true))
                .toList();
    }

    private PolicyCompanyCandidate toCompanyCandidate(CompanyProfile company, List<String> chainSegments,
                                                       boolean fetchAnnual, boolean industryMatch) {
        boolean fallback = PolicyCompanyFallbackClient.SOURCE_NAME.equals(company.dataSource());
        EastMoneyAnnualIndicator annualIndicator = fetchAnnual && !fallback ? annualIndicatorSafely(company) : null;
        BigDecimal financialScore = fallback ? null : financialQualityScore(company, annualIndicator);
        List<String> dataGaps = companyDataGaps(company, annualIndicator);
        return new PolicyCompanyCandidate(
                company.symbol(),
                company.name(),
                company.industry(),
                inferChainSegment(company, chainSegments),
                fetchAnnual ? "行业匹配研究候选，待主营核验" : "行业关键词相关，待主营核验",
                leadershipRationale(company, industryMatch),
                financialScore,
                fallback ? "财报资料待核验" : financialQualityLabel(financialScore),
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

    private List<String> leadershipRationale(CompanyProfile company, boolean industryMatch) {
        List<String> rationale = new ArrayList<>();
        if (PolicyCompanyFallbackClient.SOURCE_NAME.equals(company.dataSource())) {
            rationale.add("行业来源：新浪行业成分分类（主营及分类时效待核验）；" + company.quoteUrl());
        }
        rationale.add(industryMatch
                ? "行业关键词相关，待主营核验；不代表已取得政策相关订单"
                : "公司名称与行业关键词映射，待主营及订单核验；规则匹配度 " + company.themeRelevance());
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
                || hasProfileFinancials(company);
        BigDecimal roe = annualIndicator == null ? factor(company, "roe_annual") : nullToZero(annualIndicator.roe());
        BigDecimal cashFlow = annualIndicator == null
                ? factor(company, "operating_cash_flow_per_share")
                : nullToZero(annualIndicator.operatingCashFlowPerShare());
        if (!hasAnnual) {
            gaps.add("最近年度财报指标未匹配，需要读取年报");
        }
        if (hasAnnual && (roe.compareTo(new BigDecimal("0.08")) < 0
                || cashFlow.compareTo(BigDecimal.ZERO) <= 0)) {
            gaps.add("最近年度 ROE 或现金流质量不足，需要继续核验");
        }
        gaps.add("仍需核验主营收入中该产业方向占比");
        return gaps;
    }

    private String inferChainSegment(CompanyProfile company, List<String> chainSegments) {
        String text = company.industry() == null ? "" : company.industry().toLowerCase(Locale.ROOT);
        for (String segment : chainSegments) {
            if (text.contains(segment.toLowerCase())) {
                return segment;
            }
        }
        return "产业链环节待核验";
    }

    private BigDecimal factor(CompanyProfile company, String key) {
        return company.factors() == null ? BigDecimal.ZERO : company.factors().getOrDefault(key, BigDecimal.ZERO);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scoreFor(List<GovPolicyItem> policies, List<String> keywords) {
        List<GovPolicyItem> matched = policies.stream()
                .filter(policy -> matchesPolicy(policy, keywords))
                .toList();
        if (matched.isEmpty()) {
            return new BigDecimal("0.00");
        }

        Set<String> sources = matched.stream().map(GovPolicyItem::source).collect(Collectors.toSet());
        BigDecimal sourceReliability = matched.stream()
                .map(policy -> BigDecimal.valueOf(policy.sourceWeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(matched.size()), 2, RoundingMode.HALF_UP);
        BigDecimal crossSourceBonus = BigDecimal.valueOf(Math.min(sources.size() * 4L, 16));
        BigDecimal hitBonus = BigDecimal.valueOf(Math.min(matched.size() * 3L, 18));
        BigDecimal recencyBonus = BigDecimal.valueOf(Math.min(matched.stream().mapToInt(this::recencyPoints).sum(), 12));
        BigDecimal score = BigDecimal.valueOf(42)
                .add(sourceReliability.multiply(new BigDecimal("0.28")))
                .add(crossSourceBonus)
                .add(hitBonus)
                .add(recencyBonus);
        return score.min(new BigDecimal("96")).setScale(2, RoundingMode.HALF_UP);
    }

    private List<PolicySignal> signalsFor(List<GovPolicyItem> policies, List<String> keywords) {
        Comparator<GovPolicyItem> order = Comparator.comparing((GovPolicyItem policy) -> publication(policy).date(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingInt((GovPolicyItem policy) -> signalConfidence(policy, keywords)).reversed());
        List<GovPolicyItem> matched = policies.stream().filter(policy -> matchesPolicy(policy, keywords)).sorted(order).toList();
        // Reserve space for explicitly configured planning documents even after recent headlines accumulate.
        List<GovPolicyItem> selected = new ArrayList<>(matched.stream().filter(GovPolicyItem::planningBaseline).limit(2).toList());
        selected.addAll(matched.stream().filter(policy -> !selected.contains(policy)).limit(6 - selected.size()).toList());
        return selected.stream().sorted(order)
                .map(policy -> new PolicySignal(
                        policy.source(),
                        evidenceLabel(policy, keywords),
                        policy.title(),
                        signalConfidence(policy, keywords),
                        policy.url(),
                        policy.publishedAt(),
                        matchingExcerpt(policy, keywords),
                        policy.planningBaseline()
                ))
                .toList();
    }

    private boolean matchesPolicy(GovPolicyItem policy, List<String> keywords) {
        return containsAny(policy.title(), keywords) || containsAny(policy.bodyText(), keywords);
    }

    private String matchingExcerpt(GovPolicyItem policy, List<String> keywords) {
        if (policy.bodyText() == null || policy.bodyText().isBlank()) return null;
        // Keep complete paragraphs: a target year or condition often precedes the keyword sentence.
        String excerpt = java.util.Arrays.stream(policy.bodyText().split("\\n+"))
                .map(String::trim).filter(sentence -> containsAny(sentence, keywords)).distinct().limit(4)
                .map(sentence -> {
                    if (sentence.length() <= 1600) return sentence;
                    int match = keywords.stream().mapToInt(sentence::indexOf).filter(index -> index >= 0).min().orElse(0);
                    if (match < 1300) return sentence.substring(0, 1600) + "…";
                    int start = Math.max(300, match - 200);
                    return sentence.substring(0, 300) + "…\n" + sentence.substring(start, Math.min(sentence.length(), start + 1300)) + "…";
                }).collect(Collectors.joining("\n"));
        return excerpt.isBlank() ? null : excerpt;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        return keywords.stream().anyMatch(text::contains);
    }

    private int signalConfidence(GovPolicyItem policy, List<String> keywords) {
        String text = (policy.title() == null ? "" : policy.title()) + "\n" + (policy.bodyText() == null ? "" : policy.bodyText());
        int keywordHits = (int) keywords.stream().filter(text::contains).count();
        int confidence = 35
                + Math.min(policy.sourceWeight() / 3, 30)
                + Math.min(keywordHits * 8, 24);
        int fresh = recencyPoints(policy);
        if (fresh > 0) {
            confidence += fresh * 2;
        } else if (publication(policy).date() == null) {
            confidence -= 8;
        } else {
            confidence -= 12;
        }
        return Math.max(35, Math.min(confidence, 96));
    }

    private String evidenceLabel(GovPolicyItem policy, List<String> keywords) {
        String timing = publication(policy).date() == null ? "发布时间待核验"
                : recencyPoints(policy) > 0 ? "90天内发布" : "90天前发布";
        return (matchingExcerpt(policy, keywords) == null ? "标题关键词匹配" : "正文片段关键词匹配")
                + (policy.planningBaseline() ? " / 长期规划" : "")
                + " / 来源权重 " + policy.sourceWeight() + " / " + timing;
    }

    private int recencyPoints(GovPolicyItem policy) {
        LocalDate date = publication(policy).date();
        if (date == null) return 0;
        long age = ChronoUnit.DAYS.between(date, LocalDate.now(clock));
        if (age < 0 || age > 90) return 0;
        if (age <= 7) return 8;
        if (age <= 30) return 5;
        return 2;
    }

    private record Publication(LocalDate date, Instant instant) {
    }

    private Publication publication(GovPolicyItem policy) {
        if (policy.publishedAt() == null || policy.publishedAt().isBlank()) {
            return new Publication(null, null);
        }
        String value = policy.publishedAt().trim();
        try {
            Instant instant = OffsetDateTime.parse(value.replace(' ', 'T')).toInstant();
            return new Publication(instant.atZone(POLICY_ZONE).toLocalDate(), instant);
        } catch (DateTimeParseException ignored) {
            // Sources also provide local publication timestamps or dates without a time.
        }
        try {
            Instant instant = LocalDateTime.parse(value.replace(' ', 'T')).atZone(POLICY_ZONE).toInstant();
            return new Publication(instant.atZone(POLICY_ZONE).toLocalDate(), instant);
        } catch (DateTimeParseException ignored) {
            // Preserve unknown precision rather than inventing a publication time.
        }
        try {
            return new Publication(LocalDate.parse(value), null);
        } catch (DateTimeParseException ignored) {
            return new Publication(null, null);
        }
    }

    private List<GovPolicyItem> deduplicatePolicies(List<GovPolicyItem> policies) {
        Map<String, GovPolicyItem> unique = new LinkedHashMap<>();
        for (GovPolicyItem policy : policies) {
            if (policy == null || policy.title() == null || policy.title().isBlank()) continue;
            Publication publication = publication(policy);
            if (publication.date() != null && publication.date().isAfter(LocalDate.now(clock))) continue;
            if (publication.instant() != null && publication.instant().isAfter(clock.instant())) continue;
            String key = Normalizer.normalize(policy.title(), Normalizer.Form.NFKC)
                    .replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT);
            if (!key.isBlank()) unique.merge(key, policy, this::representativePolicy);
        }
        return List.copyOf(unique.values());
    }

    private GovPolicyItem representativePolicy(GovPolicyItem first, GovPolicyItem second) {
        // A configured planning body carries evidence that an earlier title-only list entry cannot replace.
        // Preserve that document's own source URL and webpage publication date together.
        boolean firstPlan = first.planningBaseline() && first.bodyText() != null && !first.bodyText().isBlank();
        boolean secondPlan = second.planningBaseline() && second.bodyText() != null && !second.bodyText().isBlank();
        if (firstPlan != secondPlan) return firstPlan ? first : second;
        LocalDate firstDate = publication(first).date();
        LocalDate secondDate = publication(second).date();
        // A repost must not make an old policy appear newly published.
        if (firstDate == null && secondDate != null) return second;
        if (secondDate == null && firstDate != null) return first;
        if (firstDate != null && !firstDate.equals(secondDate)) {
            return firstDate.isBefore(secondDate) ? first : second;
        }
        if (first.planningBaseline() != second.planningBaseline()) return first.planningBaseline() ? first : second;
        boolean firstBody = first.bodyText() != null && !first.bodyText().isBlank();
        boolean secondBody = second.bodyText() != null && !second.bodyText().isBlank();
        if (firstBody != secondBody) return firstBody ? first : second;
        return second.sourceWeight() > first.sourceWeight() ? second : first;
    }
}
