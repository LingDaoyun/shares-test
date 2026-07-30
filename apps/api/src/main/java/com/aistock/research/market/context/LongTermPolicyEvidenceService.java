package com.aistock.research.market.context;

import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LongTermPolicyEvidenceService {

    private static final int MAX_DOCUMENTS = 5;
    private static final int MIN_RELEVANCE_SCORE = 58;
    private static final Map<String, List<String>> INDUSTRY_KEYWORDS = Map.ofEntries(
            Map.entry("电力", List.of("电力", "电网", "能源", "发电", "电力装备", "能源保供", "碳排放")),
            Map.entry("煤炭", List.of("煤炭", "煤矿", "产能", "能源保供")),
            Map.entry("银行", List.of("银行", "金融", "信贷", "资本充足率", "存款", "贷款")),
            Map.entry("保险", List.of("保险", "金融", "偿付能力", "养老金融")),
            Map.entry("证券", List.of("证券", "资本市场", "并购重组", "上市公司")),
            Map.entry("养殖", List.of("养殖", "生猪", "畜牧", "产能", "稳产保供")),
            Map.entry("农业", List.of("农业", "农产品", "乡村振兴", "种业", "稳产保供")),
            Map.entry("食品", List.of("食品", "消费", "食品安全", "扩大内需")),
            Map.entry("纺织", List.of("纺织", "服装", "绿色制造", "消费品")),
            Map.entry("医药", List.of("医药", "医疗", "药品", "医保", "创新药")),
            Map.entry("汽车", List.of("汽车", "新能源汽车", "以旧换新", "消费")),
            Map.entry("半导体", List.of("半导体", "集成电路", "芯片", "科技创新")),
            Map.entry("软件", List.of("软件", "数字经济", "人工智能", "数据要素", "信息化")),
            Map.entry("房地产", List.of("房地产", "住房", "保障性住房", "城市更新")),
            Map.entry("有色", List.of("有色金属", "矿产资源", "稀土", "产能")),
            Map.entry("钢铁", List.of("钢铁", "产能", "节能降碳", "绿色制造")),
            Map.entry("化工", List.of("化工", "石化", "产能", "安全生产", "绿色制造"))
    );
    private static final List<String> CONSTRAINT_TERMS =
            List.of("规范", "限制", "整治", "去产能", "压减", "监管", "处罚", "淘汰", "禁止");
    private static final List<String> SUPPORT_TERMS =
            List.of("促进", "支持", "行动方案", "发展", "建设", "保供", "转型", "指导意见", "扩大内需");

    private final GovPolicyClient govPolicyClient;

    public LongTermPolicyEvidenceService(GovPolicyClient govPolicyClient) {
        this.govPolicyClient = govPolicyClient;
    }

    public LongTermPolicyEvidence evaluate(String industry, String companyName) {
        GovPolicyFetchResult fetchResult;
        try {
            fetchResult = govPolicyClient.fetchLatestPoliciesWithStatus(60);
        } catch (RuntimeException exception) {
            return new LongTermPolicyEvidence(
                    List.of(),
                    List.of("政府政策源暂不可用：" + rootMessage(exception))
            );
        }

        List<GovPolicyItem> policies = fetchResult.items();
        Set<String> keywords = keywordsFor(industry, companyName);
        LocalDate cutoff = LocalDate.now().minusYears(2);
        List<GovPolicyItem> eligiblePolicies = policies.stream()
                .filter(this::isOfficialGovernmentDocument)
                .filter(item -> isRecent(item.publishedAt(), cutoff))
                .toList();
        String normalizedIndustry = normalize(industry);
        String normalizedCompanyName = normalizeCompanyName(companyName);
        List<LongTermPolicyDocument> documents = eligiblePolicies.stream()
                .map(item -> toDocument(
                        item,
                        keywords,
                        eligiblePolicies,
                        normalizedIndustry,
                        normalizedCompanyName
                ))
                .filter(document -> !document.matchedKeywords().isEmpty())
                .filter(document -> isDirectlyRelevant(
                        document,
                        normalizedIndustry,
                        normalizedCompanyName
                ))
                .filter(document -> document.relevanceScore() >= MIN_RELEVANCE_SCORE)
                .sorted(Comparator.comparingInt(LongTermPolicyDocument::relevanceScore).reversed()
                        .thenComparing(LongTermPolicyDocument::publishedAt, Comparator.reverseOrder()))
                .limit(MAX_DOCUMENTS)
                .toList();

        if (documents.isEmpty()) {
            if (!fetchResult.failedSources().isEmpty() && policies.isEmpty()) {
                return new LongTermPolicyEvidence(
                        List.of(),
                        List.of("政府政策源暂不可用：" + String.join("；", fetchResult.failedSources()))
                );
            }
            List<String> dataGaps = new java.util.ArrayList<>();
            dataGaps.add("最近两年未匹配到可靠官方政策文件");
            if (!fetchResult.failedSources().isEmpty()) {
                dataGaps.add("部分政策源不可用：" + String.join("；", fetchResult.failedSources()));
            }
            return new LongTermPolicyEvidence(
                    List.of(),
                    List.copyOf(dataGaps)
            );
        }
        List<String> dataGaps = fetchResult.failedSources().isEmpty()
                ? List.of()
                : List.of("部分政策源不可用：" + String.join("；", fetchResult.failedSources()));
        return new LongTermPolicyEvidence(documents, dataGaps);
    }

    private boolean isDirectlyRelevant(
            LongTermPolicyDocument document,
            String normalizedIndustry,
            String normalizedCompanyName
    ) {
        return isDirectlyRelevant(
                document.title(),
                document.matchedKeywords(),
                normalizedIndustry,
                normalizedCompanyName
        );
    }

    private boolean isDirectlyRelevant(
            String title,
            List<String> matchedKeywords,
            String normalizedIndustry,
            String normalizedCompanyName
    ) {
        if (matchedKeywords.size() >= 2) {
            return true;
        }
        if (normalizedIndustry.length() >= 2 && title.contains(normalizedIndustry)) {
            return true;
        }
        return normalizedCompanyName.length() >= 4 && title.contains(normalizedCompanyName);
    }

    private LongTermPolicyDocument toDocument(
            GovPolicyItem item,
            Set<String> keywords,
            List<GovPolicyItem> policies,
            String normalizedIndustry,
            String normalizedCompanyName
    ) {
        List<String> matchedKeywords = keywords.stream()
                .filter(keyword -> item.title().contains(keyword))
                .toList();
        int agreementSources = (int) policies.stream()
                .filter(other -> !item.source().equals(other.source()))
                .filter(other -> isDirectlyRelevant(
                        other.title(),
                        keywords.stream()
                                .filter(keyword -> other.title().contains(keyword))
                                .toList(),
                        normalizedIndustry,
                        normalizedCompanyName
                ))
                .filter(other -> matchedKeywords.stream().anyMatch(keyword -> other.title().contains(keyword)))
                .map(GovPolicyItem::source)
                .distinct()
                .count();
        int freshnessScore = freshnessScore(item.publishedAt());
        int relevanceScore = Math.min(
                100,
                34
                        + Math.min(36, matchedKeywords.size() * 12)
                        + Math.min(20, item.sourceWeight() / 5)
                        + freshnessScore
                        + Math.min(6, agreementSources * 3)
        );
        String impact = classifyImpact(item.title());
        String rationale = matchedKeywords.isEmpty()
                ? "未找到与行业直接对应的关键词"
                : "标题命中行业关键词：" + String.join("、", matchedKeywords)
                + "；政策倾向：" + impactLabel(impact)
                + (agreementSources > 0 ? "；另有" + agreementSources + "个官方来源命中同类主题" : "");
        return new LongTermPolicyDocument(
                item.title(),
                item.source(),
                item.publishedAt(),
                item.url(),
                impact,
                relevanceScore,
                matchedKeywords,
                rationale
        );
    }

    private Set<String> keywordsFor(String industry, String companyName) {
        String normalizedIndustry = normalize(industry);
        Set<String> keywords = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : INDUSTRY_KEYWORDS.entrySet()) {
            if (normalizedIndustry.contains(entry.getKey())) {
                keywords.addAll(entry.getValue());
            }
        }
        if (normalizedIndustry.length() >= 2) {
            keywords.add(normalizedIndustry);
        }
        String normalizedCompanyName = normalizeCompanyName(companyName);
        if (normalizedCompanyName.length() >= 4) {
            keywords.add(normalizedCompanyName);
        }
        return keywords;
    }

    private String classifyImpact(String title) {
        if (containsAny(title, CONSTRAINT_TERMS)) {
            return "CONSTRAINT";
        }
        if (containsAny(title, SUPPORT_TERMS)) {
            return "SUPPORT";
        }
        return "NEUTRAL";
    }

    private boolean isOfficialGovernmentDocument(GovPolicyItem item) {
        if (item == null || item.title() == null || item.url() == null) {
            return false;
        }
        try {
            String host = URI.create(item.url()).getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.equals("gov.cn") || normalizedHost.endsWith(".gov.cn");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isRecent(String publishedAt, LocalDate cutoff) {
        if (publishedAt == null || publishedAt.length() < 10) {
            return false;
        }
        try {
            LocalDate publishedDate = LocalDate.parse(publishedAt.substring(0, 10));
            return !publishedDate.isBefore(cutoff) && !publishedDate.isAfter(LocalDate.now());
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private int freshnessScore(String publishedAt) {
        LocalDate publishedDate = LocalDate.parse(publishedAt.substring(0, 10));
        long ageDays = java.time.temporal.ChronoUnit.DAYS.between(publishedDate, LocalDate.now());
        if (ageDays <= 180) {
            return 6;
        }
        if (ageDays <= 365) {
            return 4;
        }
        return 2;
    }

    private boolean containsAny(String text, List<String> terms) {
        return text != null && terms.stream().anyMatch(text::contains);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩA-Z\\s()（）/-]", "");
    }

    private String normalizeCompanyName(String companyName) {
        if (companyName == null) {
            return "";
        }
        return companyName.replaceAll("(股份|集团|控股|有限|公司|A股|Ａ股)", "").trim();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private String impactLabel(String impact) {
        return switch (impact) {
            case "SUPPORT" -> "支持";
            case "CONSTRAINT" -> "约束";
            default -> "中性";
        };
    }
}
