package com.aistock.research.evidence;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.filing.FilingDocument;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.EvidenceTier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class AgentEvidenceSearchService {

    public List<AgentEvidenceCheck> audit(
            CompanyResearchView research,
            String agentCode,
            List<String> requirements
    ) {
        return requirements.stream()
                .map(requirement -> check(research, agentCode, requirement))
                .toList();
    }

    public List<String> missingEvidenceObjections(List<AgentEvidenceCheck> checks) {
        return checks.stream()
                .filter(check -> "MISSING".equals(check.status()))
                .map(check -> "证据搜索未找到：" + check.requirement() + "，该评分依据不存在或待补证")
                .toList();
    }

    private AgentEvidenceCheck check(CompanyResearchView research, String agentCode, String requirement) {
        return switch (agentCode) {
            case "POLICY_STRATEGIST" -> policyEvidence(research, requirement);
            case "FINANCIAL_AUDITOR" -> financialEvidence(research, requirement);
            case "MOAT_INVESTIGATOR" -> moatEvidence(research, requirement);
            case "VALUATION_DISCIPLINARIAN" -> valuationEvidence(research, requirement);
            case "RISK_CONTRARIAN" -> riskEvidence(research, requirement);
            default -> genericEvidence(research, requirement, List.of());
        };
    }

    private AgentEvidenceCheck policyEvidence(CompanyResearchView research, String requirement) {
        if (containsAny(requirement, "主营", "收入", "产品", "行业拆分")) {
            return genericEvidence(
                    research,
                    requirement,
                    List.of("主营", "收入", "产品", "业务", "行业", "占比"),
                    candidate -> !containsAny(candidate.source(), "主题映射", "证据等级", "公司画像")
                            && containsAny(candidate.text(), "主营", "收入")
                            && containsAny(candidate.text(), "产品", "业务", "行业", "占比")
            );
        }
        return genericEvidence(research, requirement, List.of("政策", "资金", "补贴", "招投标", "中标", "订单", "合同", "项目"));
    }

    private AgentEvidenceCheck financialEvidence(CompanyResearchView research, String requirement) {
        CompanyProfile company = research.company();
        if (containsAny(requirement, "近 10 年", "ROE", "现金流", "毛利率", "营收")) {
            if (company.financialReportDate() == null || company.financialReportDate().isBlank()) {
                return missing(requirement, "东方财富年报指标", "未搜索到最近年度年报指标，更没有形成 10 年序列");
            }
            return partial(
                    requirement,
                    "东方财富年报指标",
                    "已找到最近年度年报指标 " + company.financialReportDate() + "，但尚未形成 10 年连续序列",
                    company.quoteUrl(),
                    62
            );
        }
        return genericEvidence(research, requirement, List.of("一次性收益", "商誉", "减值", "研发资本化", "会计差错"));
    }

    private AgentEvidenceCheck moatEvidence(CompanyResearchView research, String requirement) {
        if (containsAny(requirement, "核心产品", "收入占比")) {
            return genericEvidence(research, requirement, List.of("核心产品", "收入", "占比", "主营", "产品"));
        }
        if (containsAny(requirement, "专利", "认证", "产能", "项目")) {
            return genericEvidence(research, requirement, List.of("专利", "认证", "产能", "项目", "技术", "研发", "客户"));
        }
        return genericEvidence(research, requirement, List.of("重大合同", "交付", "回款", "中标", "订单", "客户"));
    }

    private AgentEvidenceCheck valuationEvidence(CompanyResearchView research, String requirement) {
        CompanyProfile company = research.company();
        if (containsAny(requirement, "分位", "3/5/10")) {
            String text = "当前只搜索到实时估值口径：PE(TTM) " + valueOrUnknown(company.peTtm())
                    + "，PB " + valueOrUnknown(company.pbRatio()) + "，未搜索到历史估值分位";
            return missing(requirement, "实时行情/估值源", text);
        }
        if (containsAny(requirement, "自由现金流")) {
            return genericEvidence(research, requirement, List.of("自由现金流", "经营现金流", "现金流"));
        }
        if (company.peTtm() != null || company.pbRatio() != null) {
            return partial(
                    requirement,
                    "腾讯/东方财富实时行情",
                    "已找到 PE/PB 实时估值，但未搜索到完整同业可比估值表",
                    company.quoteUrl(),
                    58
            );
        }
        return missing(requirement, "腾讯/东方财富实时行情", "未搜索到可用估值口径");
    }

    private AgentEvidenceCheck riskEvidence(CompanyResearchView research, String requirement) {
        List<String> keywords = riskKeywords(requirement);
        List<EvidenceCandidate> candidates = collectCandidates(research).stream()
                .filter(candidate -> isRiskSource(candidate.source()))
                .filter(candidate -> keywords.stream().anyMatch(keyword -> containsAny(candidate.text(), keyword)))
                .sorted(Comparator.comparing(EvidenceCandidate::confidence).reversed())
                .toList();
        if (!candidates.isEmpty()) {
            EvidenceCandidate best = candidates.get(0);
            return found(requirement, best.source(), best.text(), best.url(), best.confidence());
        }
        if ("LIVE".equals(research.filingEvidence().status()) && research.filingEvidence().totalDocuments() > 0) {
            return missing(
                    requirement,
                    "巨潮公告在线检索",
                    "已搜索公告标题和可解析正文，未命中该项风险评分依据"
            );
        }
        return missing(requirement, "巨潮/交易所公告", "未搜索到足够公告正文，风险评分依据不完整");
    }

    private AgentEvidenceCheck genericEvidence(CompanyResearchView research, String requirement, List<String> keywords) {
        return genericEvidence(research, requirement, keywords, ignored -> true);
    }

    private AgentEvidenceCheck genericEvidence(
            CompanyResearchView research,
            String requirement,
            List<String> keywords,
            Predicate<EvidenceCandidate> candidateFilter
    ) {
        List<String> effectiveKeywords = keywords.isEmpty() ? keywordsFrom(requirement) : keywords;
        List<EvidenceCandidate> candidates = collectCandidates(research).stream()
                .filter(candidateFilter)
                .filter(candidate -> !looksLikeEvidenceGap(candidate.text()))
                .filter(candidate -> effectiveKeywords.stream().anyMatch(keyword -> containsAny(candidate.text(), keyword)))
                .sorted(Comparator.comparing(EvidenceCandidate::confidence).reversed())
                .toList();
        if (!candidates.isEmpty()) {
            EvidenceCandidate best = candidates.get(0);
            return found(requirement, best.source(), best.text(), best.url(), best.confidence());
        }

        if (hasOnlineSources(research)) {
            return missing(requirement, "在线证据源", "已搜索政策、行情、年报指标和公告证据，未命中该项评分依据");
        }
        return missing(requirement, "在线证据源", "当前在线证据不足，无法确认该项评分依据");
    }

    private List<EvidenceCandidate> collectCandidates(CompanyResearchView research) {
        List<EvidenceCandidate> candidates = new ArrayList<>();
        research.hardBlocks().forEach(block ->
                candidates.add(new EvidenceCandidate("规则硬风险", block, null, 86)));
        for (EvidenceItem item : research.company().evidence()) {
            candidates.add(new EvidenceCandidate(
                    item.sourceType() + " / " + item.sourceTitle(),
                    join(item.sourceTitle(), item.excerpt()),
                    item.url(),
                    item.confidence()
            ));
        }
        for (FilingDocument document : research.filingEvidence().documents()) {
            candidates.add(new EvidenceCandidate(
                    "巨潮公告 / " + document.title(),
                    join(document.title(), String.join(" ", document.matchedKeywords())),
                    document.sourceUrl(),
                    document.confidence()
            ));
        }
        for (FilingEvent event : research.filingEvidence().extractedEvents()) {
            candidates.add(new EvidenceCandidate(
                    "公告正文事件 / " + event.documentTitle(),
                    join(event.eventLabel(), event.evidenceText()),
                    event.sourceUrl(),
                    event.confidence()
            ));
        }
        research.filingEvidence().moatSignals().forEach(signal ->
                candidates.add(new EvidenceCandidate("巨潮公告壁垒线索", signal, null, 72)));
        research.filingEvidence().validationSignals().forEach(signal ->
                candidates.add(new EvidenceCandidate("巨潮公告兑现线索", signal, null, 72)));
        research.filingEvidence().riskSignals().forEach(signal ->
                candidates.add(new EvidenceCandidate("巨潮公告风险线索", signal, null, 72)));
        for (EvidenceTier tier : research.evidenceTiers()) {
            for (String ref : tier.evidenceRefs()) {
                candidates.add(new EvidenceCandidate(tier.label(), ref, null, tier.strength()));
            }
        }
        research.company().coreAssets().forEach(asset ->
                candidates.add(new EvidenceCandidate("公司画像核心资产", asset, research.company().quoteUrl(), 58)));
        research.company().risks().forEach(risk ->
                candidates.add(new EvidenceCandidate("公司画像风险", risk, research.company().quoteUrl(), 58)));
        return candidates;
    }

    private List<String> riskKeywords(String requirement) {
        if (containsAny(requirement, "监管处罚", "问询函", "诉讼", "质押", "减持")) {
            return List.of("监管处罚", "问询函", "诉讼", "仲裁", "质押", "减持", "立案", "处罚", "警示函");
        }
        if (containsAny(requirement, "审计意见", "会计差错")) {
            return List.of("审计意见", "会计差错", "非标", "保留意见", "无法表示", "内部控制", "内控缺陷");
        }
        if (containsAny(requirement, "应收账款", "商誉减值")) {
            return List.of("应收账款", "商誉", "减值", "坏账", "信用减值");
        }
        return keywordsFrom(requirement);
    }

    private boolean isRiskSource(String source) {
        return source != null && (source.contains("风险") || source.contains("硬风险") || source.contains("公告"));
    }

    private boolean looksLikeEvidenceGap(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text, "仍需", "尚需", "待补充", "待验证", "需公告", "无法确认", "未搜索到", "缺少");
    }

    private boolean hasOnlineSources(CompanyResearchView research) {
        return research.company().liveData()
                || "LIVE".equals(research.filingEvidence().status())
                || research.company().financialReportDate() != null;
    }

    private String firstDocumentUrl(CompanyResearchView research) {
        return research.filingEvidence().documents().stream()
                .map(FilingDocument::sourceUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
    }

    private AgentEvidenceCheck found(String requirement, String source, String text, String url, int confidence) {
        return new AgentEvidenceCheck(requirement, "FOUND", "已找到", source, summarize(text), url, confidence);
    }

    private AgentEvidenceCheck partial(String requirement, String source, String text, String url, int confidence) {
        return new AgentEvidenceCheck(requirement, "PARTIAL", "部分找到", source, summarize(text), url, confidence);
    }

    private AgentEvidenceCheck missing(String requirement, String source, String text) {
        return new AgentEvidenceCheck(requirement, "MISSING", "未找到", source, summarize(text), null, 0);
    }

    private List<String> keywordsFrom(String requirement) {
        return List.of(requirement.split("[/、，, 和]+")).stream()
                .map(String::trim)
                .filter(Predicate.not(String::isBlank))
                .toList();
    }

    private boolean containsAny(String text, String... keywords) {
        return containsAny(text, List.of(keywords));
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private String join(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("："));
    }

    private String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "未找到可用证据";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180) + "...";
    }

    private String valueOrUnknown(BigDecimal value) {
        return value == null ? "待补充" : value.toString();
    }

    private record EvidenceCandidate(String source, String text, String url, int confidence) {
    }
}
