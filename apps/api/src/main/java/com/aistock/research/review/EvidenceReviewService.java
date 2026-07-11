package com.aistock.research.review;

import com.aistock.research.committee.AgentCommitteeService;
import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.committee.AgentOpinion;
import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.evidence.AgentEvidenceCheck;
import com.aistock.research.filing.FilingDocument;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.filing.FilingEvidenceSummary;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.financial.FinancialHistoryService;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.valuation.PeerValuationReport;
import com.aistock.research.valuation.ValuationHistoryReport;
import com.aistock.research.valuation.ValuationHistoryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class EvidenceReviewService {

    private final CompanyService companyService;
    private final CompanyResearchService companyResearchService;
    private final AgentCommitteeService agentCommitteeService;
    private final FinancialHistoryService financialHistoryService;
    private final ValuationHistoryService valuationHistoryService;

    public EvidenceReviewService(
            CompanyService companyService,
            CompanyResearchService companyResearchService,
            AgentCommitteeService agentCommitteeService,
            FinancialHistoryService financialHistoryService,
            ValuationHistoryService valuationHistoryService
    ) {
        this.companyService = companyService;
        this.companyResearchService = companyResearchService;
        this.agentCommitteeService = agentCommitteeService;
        this.financialHistoryService = financialHistoryService;
        this.valuationHistoryService = valuationHistoryService;
    }

    public EvidenceReviewReport review(String symbol) {
        CompanyProfile company = companyService.getCompany(symbol);
        CompanyResearchView research = companyResearchService.analyze(company);
        AgentConsensusReport consensus = agentCommitteeService.discuss(symbol);
        List<EvidenceReviewItem> items = consensus.opinions().stream()
                .flatMap(opinion -> opinion.evidenceChecks().stream()
                        .filter(check -> !"FOUND".equals(check.status()))
                        .map(check -> reviewItem(research, opinion, check)))
                .sorted(Comparator.comparing(EvidenceReviewItem::reviewStatus)
                        .thenComparing(EvidenceReviewItem::agentName)
                        .thenComparing(EvidenceReviewItem::requirement))
                .toList();

        int verified = count(items, "VERIFIED");
        int partial = count(items, "PARTIAL");
        int notFound = count(items, "NOT_FOUND");
        int blocked = count(items, "BLOCKED");
        ReviewDecision decision = decide(items, consensus);
        List<EvidenceReviewStep> steps = steps(research, consensus, items);
        return new EvidenceReviewReport(
                consensus.symbol(),
                consensus.companyName(),
                decision.stage(),
                decision.label(),
                items.size(),
                verified,
                partial,
                notFound,
                blocked,
                consensus,
                items,
                steps,
                conclusions(items, decision),
                Instant.now()
        );
    }

    private EvidenceReviewItem reviewItem(CompanyResearchView research, AgentOpinion opinion, AgentEvidenceCheck check) {
        String requirement = check.requirement();
        if (containsAny(requirement, "主营收入", "收入占比", "核心产品")) {
            return annualReportReview(research, opinion, check, "主营/产品收入拆分");
        }
        if (containsAny(requirement, "政策资金", "招投标", "订单兑现", "重大合同", "交付", "回款")) {
            return filingSignalReview(
                    research,
                    opinion,
                    check,
                    "公告兑现线索",
                    List.of("政策", "资金", "补贴", "招投标", "中标", "订单", "合同", "项目", "交付", "回款")
            );
        }
        if (containsAny(requirement, "近 10 年", "ROE", "毛利率", "营收")) {
            return financialSequenceReview(research, opinion, check);
        }
        if (containsAny(requirement, "一次性收益", "商誉减值", "研发资本化", "会计差错")) {
            return annualReportReview(research, opinion, check, "财务附注/会计质量");
        }
        if (containsAny(requirement, "专利", "客户认证", "产能项目", "项目原文")) {
            return filingSignalReview(
                    research,
                    opinion,
                    check,
                    "公告壁垒线索",
                    List.of("专利", "认证", "客户", "产能", "募投", "项目", "核心技术", "研发")
            );
        }
        if (containsAny(requirement, "估值分位", "3/5/10")) {
            return valuationHistoryReview(research, opinion, check);
        }
        if (containsAny(requirement, "自由现金流")) {
            return cashFlowReview(research, opinion, check);
        }
        if (containsAny(requirement, "同业可比估值")) {
            return peerValuationReview(research, opinion, check);
        }
        if (containsAny(requirement, "监管处罚", "问询函", "诉讼", "质押", "减持", "审计意见", "会计差错", "应收账款", "商誉减值")) {
            return filingSignalReview(
                    research,
                    opinion,
                    check,
                    "公告风险线索",
                    riskKeywords(requirement)
            );
        }
        return genericReview(research, opinion, check);
    }

    private EvidenceReviewItem annualReportReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String scope
    ) {
        Optional<FilingDocument> report = annualReport(research.filingEvidence());
        if (report.isPresent()) {
            FilingDocument document = report.get();
            String parsed = research.filingEvidence().parsedDocuments() > 0 ? "已解析部分正文" : "暂未解析到结构化正文";
            return partial(
                    opinion,
                    check,
                    "巨潮年报/定期报告",
                    "巨潮资讯 / " + document.title(),
                    document.title() + " 已定位，" + parsed + "，仍需抽取分产品/行业收入表或财务附注原文。",
                    document.downloadUrl() == null ? document.sourceUrl() : document.downloadUrl(),
                    64,
                    scope + "已有入口，但还不能自动证明评分项",
                    "打开年报 PDF，抽取主营构成、产品收入占比和相关财务附注"
            );
        }
        if ("LIVE".equals(research.filingEvidence().status())) {
            return notFound(
                    opinion,
                    check,
                    "巨潮公告列表",
                    "已检索最近公告，但未定位到年度报告或可证明该项的定期报告。",
                    "扩大公告检索区间，或接入巨潮按公告类别检索年报"
            );
        }
        return blocked(
                opinion,
                check,
                "公告源",
                "当前没有可用年报原文入口，无法复核 " + check.requirement() + "。",
                "先修复公告源或接入年报 PDF 数据源"
        );
    }

    private EvidenceReviewItem filingSignalReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String scope,
            List<String> keywords
    ) {
        Optional<FilingEvidence> evidence = filingEvidence(research.filingEvidence(), keywords);
        if (evidence.isPresent()) {
            FilingEvidence item = evidence.get();
            return verified(
                    opinion,
                    check,
                    scope,
                    item.source(),
                    item.text(),
                    item.url(),
                    item.confidence(),
                    "已在公告链路中复核到直接线索",
                    "继续打开原文确认金额、期限、交付和回款条件"
            );
        }
        if ("LIVE".equals(research.filingEvidence().status()) && research.filingEvidence().totalDocuments() > 0) {
            return notFound(
                    opinion,
                    check,
                    scope,
                    "已搜索巨潮公告标题和已解析正文，未命中 " + check.requirement() + "。",
                    "扩大公告关键词和时间范围，必要时接入交易所互动易/招投标数据"
            );
        }
        return blocked(
                opinion,
                check,
                scope,
                "公告源未能提供可检索样本，无法复核 " + check.requirement() + "。",
                "先补齐巨潮公告源或公司公告镜像源"
        );
    }

    private EvidenceReviewItem financialSequenceReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check
    ) {
        FinancialHistoryReport history = financialHistoryService.history(research.company().symbol(), 10);
        if (history.annualPointCount() >= 8) {
            return verified(
                    opinion,
                    check,
                    "东方财富历史年报指标",
                    history.statusLabel(),
                    "已获取 " + history.annualPointCount() + " 个年度样本，平均 ROE "
                            + valueOrUnknown(history.averageRoe())
                            + "，平均毛利率 " + valueOrUnknown(history.averageGrossMargin())
                            + "，经营现金流/股为正年份 " + history.positiveCashFlowYears()
                            + "。结论：" + String.join("；", history.conclusions()),
                    research.company().quoteUrl(),
                    82,
                    "多年财务序列已可支撑质量复核",
                    "继续补资本开支、主营拆分和一次性损益，完善自由现金流与会计质量判断"
            );
        }
        if (history.annualPointCount() > 0) {
            return partial(
                    opinion,
                    check,
                    "东方财富历史年报指标",
                    history.statusLabel(),
                    "已获取 " + history.annualPointCount() + " 个年度样本，财务历史质量分 "
                            + history.qualityScore() + "。结论：" + String.join("；", history.conclusions()),
                    research.company().quoteUrl(),
                    history.annualPointCount() >= 5 ? 74 : 64,
                    "财务序列部分可用，但尚未满足 8-10 年长线复核目标",
                    String.join("；", history.dataGaps())
            );
        }
        return blocked(
                opinion,
                check,
                "东方财富年报指标",
                "当前样本没有匹配最近年度财务指标，更无法构建 10 年序列。",
                "先扩大财务指标抓取范围，再做跨周期质量复核"
        );
    }

    private EvidenceReviewItem valuationHistoryReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check
    ) {
        CompanyProfile company = research.company();
        ValuationHistoryReport valuationHistory = valuationHistoryService.history(company.symbol(), 10);
        if (valuationHistory.sampleCount() >= 5) {
            return verified(
                    opinion,
                    check,
                    "年末估值分位",
                    valuationHistory.statusLabel(),
                    "已生成 " + valuationHistory.sampleCount() + " 个年度估值样本；当前 PE(TTM) "
                            + valueOrUnknown(valuationHistory.currentPe())
                            + "，PE 年度分位 " + percentText(valuationHistory.pePercentile())
                            + "；当前 PB " + valueOrUnknown(valuationHistory.currentPb())
                            + "，PB 年度分位 " + percentText(valuationHistory.pbPercentile())
                            + "。结论：" + String.join("；", valuationHistory.conclusions()),
                    company.quoteUrl(),
                    valuationHistory.sampleCount() >= 8 ? 80 : 72,
                    "已补齐年度估值分位，可支撑长线估值复核",
                    valuationHistory.peerValuation().peerCount() >= 3
                            ? "继续接入日频/月频 PE/PB，提高估值纪律精度"
                            : "继续接入完整行业成分股，提高同业估值覆盖"
            );
        }
        FinancialHistoryReport history = financialHistoryService.history(company.symbol(), 10);
        if (company.peTtm() != null || company.pbRatio() != null) {
            return partial(
                    opinion,
                    check,
                    "实时估值源 + 历史财务序列",
                    history.statusLabel(),
                    "当前 PE(TTM) " + valueOrUnknown(company.peTtm()) + "，PB " + valueOrUnknown(company.pbRatio())
                            + "；已补 " + history.annualPointCount() + " 个年度财务样本，但复核未找到 3/5/10 年历史 PE/PB 分位序列。",
                    company.quoteUrl(),
                    history.annualPointCount() >= 5 ? 64 : 58,
                    "当前估值与历史财务质量可交叉查看，但还不能证明历史估值分位",
                    "接入历史 PE/PB 日频或月频序列，并按行业计算分位"
            );
        }
        return blocked(
                opinion,
                check,
                "估值源",
                "当前连实时 PE/PB 也缺失，无法计算估值分位。",
                "先修复实时行情估值口径，再补历史分位"
        );
    }

    private EvidenceReviewItem cashFlowReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check
    ) {
        FinancialHistoryReport history = financialHistoryService.history(research.company().symbol(), 10);
        if (history.positiveCashFlowYears() > 0) {
            return partial(
                    opinion,
                    check,
                    "东方财富历史年报指标",
                    "经营现金流/股序列",
                    "已获取 " + history.annualPointCount() + " 个年度样本，其中经营现金流/股为正年份 "
                            + history.positiveCashFlowYears() + " 个；但仍缺资本开支和市值历史，未计算严格自由现金流收益率。",
                    research.company().quoteUrl(),
                    history.annualPointCount() >= 5 ? 68 : 60,
                    "经营现金流序列入口存在，但还不是自由现金流收益率",
                    "继续接入资本开支、总市值和自由现金流收益率计算公式"
            );
        }
        return blocked(
                opinion,
                check,
                "现金流指标",
                "未找到经营现金流指标，无法复核自由现金流收益率。",
                "补齐现金流量表或年报指标历史数据"
        );
    }

    private EvidenceReviewItem peerValuationReview(
            CompanyResearchView research,
            AgentOpinion opinion,
            AgentEvidenceCheck check
    ) {
        ValuationHistoryReport valuationHistory = valuationHistoryService.history(research.company().symbol(), 10);
        PeerValuationReport peerValuation = valuationHistory.peerValuation();
        if (peerValuation.peerCount() >= 3 && "INDUSTRY".equals(peerValuation.scope())) {
            return verified(
                    opinion,
                    check,
                    "同业估值分布",
                    peerValuation.scopeLabel(),
                    "已形成 " + peerValuation.peerCount() + " 个同行业可比样本；PE 中位数 "
                            + valueOrUnknown(peerValuation.medianPe())
                            + "，当前 PE 同业分位 " + percentText(peerValuation.pePeerPercentile())
                            + "；PB 中位数 " + valueOrUnknown(peerValuation.medianPb())
                            + "，当前 PB 同业分位 " + percentText(peerValuation.pbPeerPercentile())
                            + "。结论：" + String.join("；", peerValuation.conclusions()),
                    research.company().quoteUrl(),
                    peerValuation.peerCount() >= 5 ? 82 : 76,
                    "同业比较链条已形成，可作为估值纪律的横向证据",
                    "继续扩大行业成分股覆盖，并对异常高/低 PE 样本做人工剔除"
            );
        }
        if (peerValuation.peerCount() >= 3) {
            return partial(
                    opinion,
                    check,
                    "同业估值分布",
                    peerValuation.scopeLabel(),
                    "已形成 " + peerValuation.peerCount() + " 个可比样本，但当前口径为 " + peerValuation.scopeLabel()
                            + "；PE 中位数 " + valueOrUnknown(peerValuation.medianPe())
                            + "，当前 PE 同业分位 " + percentText(peerValuation.pePeerPercentile())
                            + "；PB 中位数 " + valueOrUnknown(peerValuation.medianPb())
                            + "，当前 PB 同业分位 " + percentText(peerValuation.pbPeerPercentile())
                            + "。缺口：" + String.join("；", peerValuation.dataGaps()),
                    research.company().quoteUrl(),
                    70,
                    "横向估值可见，但同行业覆盖仍不完整",
                    "接入完整行业成分股，替换主题或全市场兜底样本"
            );
        }
        if (research.company().peTtm() != null || research.company().pbRatio() != null) {
            return partial(
                    opinion,
                    check,
                    "实时估值源",
                    "公司当前 PE/PB",
                    "已找到当前 PE/PB，但同业可比公司集合、分位和行业中位数尚未建立。",
                    research.company().quoteUrl(),
                    56,
                    "单公司估值可见，同业比较链条未完成",
                    "按申万/证监会行业构建同业池并计算中位数"
            );
        }
        return blocked(
                opinion,
                check,
                "同业估值源",
                "当前公司估值口径缺失，无法进入同业比较。",
                "补实时估值字段后再构建同业比较"
        );
    }

    private EvidenceReviewItem genericReview(CompanyResearchView research, AgentOpinion opinion, AgentEvidenceCheck check) {
        Optional<FilingEvidence> evidence = filingEvidence(research.filingEvidence(), keywordsFrom(check.requirement()));
        if (evidence.isPresent()) {
            FilingEvidence item = evidence.get();
            return verified(opinion, check, "综合在线证据", item.source(), item.text(), item.url(), item.confidence(),
                    "复核链路找到相关证据", "人工确认该证据是否足以支撑原评分项");
        }
        return notFound(
                opinion,
                check,
                "综合在线证据",
                "已搜索公告、年报指标、行情估值和公司画像，未找到 " + check.requirement() + "。",
                "增加垂直数据源或人工上传原文材料"
        );
    }

    private List<EvidenceReviewStep> steps(
            CompanyResearchView research,
            AgentConsensusReport consensus,
            List<EvidenceReviewItem> items
    ) {
        return List.of(
                new EvidenceReviewStep(
                        "COLLECT_GAPS",
                        "复核编排器",
                        "从五个 Agent 的证据检查中提取 " + items.size() + " 个待复核项。",
                        consensus.requiredEvidence().stream().limit(6).toList()
                ),
                new EvidenceReviewStep(
                        "SEARCH_ONLINE_SOURCES",
                        "在线证据搜索器",
                        "已搜索巨潮公告、公告正文事件、东方财富年报指标、实时估值和政策/主题证据。",
                        List.of(
                                "公告状态：" + research.filingEvidence().statusLabel(),
                                "公告样本：" + research.filingEvidence().totalDocuments(),
                                "正文解析：" + research.filingEvidence().parsedDocuments()
                        )
                ),
                new EvidenceReviewStep(
                        "CLASSIFY_EVIDENCE",
                        "证据裁判",
                        "把复核结果拆为已核实、部分补到、未命中和数据源阻塞。",
                        List.of(
                                "已核实 " + count(items, "VERIFIED"),
                                "部分补到 " + count(items, "PARTIAL"),
                                "未命中 " + count(items, "NOT_FOUND"),
                                "源阻塞 " + count(items, "BLOCKED")
                        )
                ),
                new EvidenceReviewStep(
                        "AGENT_REVIEW_GATE",
                        "Agent 升级门",
                        "只有关键证据补齐后，候选才允许从证据复核升级到观察或等待价格。",
                        items.stream()
                                .filter(item -> !"VERIFIED".equals(item.reviewStatus()))
                                .map(EvidenceReviewItem::nextAction)
                                .distinct()
                                .limit(5)
                                .toList()
                )
        );
    }

    private ReviewDecision decide(List<EvidenceReviewItem> items, AgentConsensusReport consensus) {
        if (items.isEmpty()) {
            return new ReviewDecision("CLEAR", "复核通过");
        }
        int unresolved = count(items, "NOT_FOUND") + count(items, "BLOCKED");
        if (unresolved == 0 && count(items, "PARTIAL") <= 2 && consensus.vetoCount() == 0) {
            return new ReviewDecision("UPGRADE_READY", "可升级观察");
        }
        if (count(items, "VERIFIED") + count(items, "PARTIAL") >= Math.max(2, items.size() / 2) && consensus.vetoCount() == 0) {
            return new ReviewDecision("PARTIAL_REVIEWED", "部分补证");
        }
        if (count(items, "BLOCKED") > 0) {
            return new ReviewDecision("SOURCE_BLOCKED", "数据源阻塞");
        }
        return new ReviewDecision("EVIDENCE_GAP", "继续补证");
    }

    private List<String> conclusions(List<EvidenceReviewItem> items, ReviewDecision decision) {
        List<String> conclusions = new ArrayList<>();
        conclusions.add(decision.label());
        if (items.isEmpty()) {
            conclusions.add("五个 Agent 当前没有待复核证据项。");
            return conclusions;
        }
        if (count(items, "VERIFIED") > 0) {
            conclusions.add("已核实 " + count(items, "VERIFIED") + " 项，可作为后续讨论的正向证据。");
        }
        if (count(items, "PARTIAL") > 0) {
            conclusions.add("部分补到 " + count(items, "PARTIAL") + " 项，需要人工读取原文或补历史序列。");
        }
        if (count(items, "NOT_FOUND") > 0) {
            conclusions.add("搜索未命中 " + count(items, "NOT_FOUND") + " 项，这些评分依据当前不存在或待补证。");
        }
        if (count(items, "BLOCKED") > 0) {
            conclusions.add("数据源阻塞 " + count(items, "BLOCKED") + " 项，需要先接入对应数据源。");
        }
        return conclusions;
    }

    private Optional<FilingDocument> annualReport(FilingEvidenceSummary filing) {
        return filing.documents().stream()
                .filter(document -> "年度报告".equals(document.category())
                        || containsAny(document.title(), "年度报告", "年报", "半年报", "季度报告"))
                .findFirst();
    }

    private Optional<FilingEvidence> filingEvidence(FilingEvidenceSummary filing, List<String> keywords) {
        Optional<FilingEvidence> event = filing.extractedEvents().stream()
                .filter(item -> containsAny(item.evidenceText() + item.documentTitle(), keywords))
                .map(item -> new FilingEvidence(
                        "公告正文事件 / " + item.documentTitle(),
                        item.eventLabel() + "：" + item.evidenceText(),
                        item.sourceUrl(),
                        item.confidence()
                ))
                .findFirst();
        if (event.isPresent()) {
            return event;
        }

        Optional<FilingEvidence> document = filing.documents().stream()
                .filter(item -> containsAny(item.title() + String.join(" ", item.matchedKeywords()), keywords))
                .map(item -> new FilingEvidence(
                        "巨潮公告 / " + item.title(),
                        item.title() + " / " + String.join("、", item.matchedKeywords()),
                        item.sourceUrl(),
                        item.confidence()
                ))
                .findFirst();
        if (document.isPresent()) {
            return document;
        }

        return filing.validationSignals().stream()
                .filter(item -> containsAny(item, keywords))
                .findFirst()
                .map(item -> new FilingEvidence("巨潮公告兑现线索", item, null, 72))
                .or(() -> filing.moatSignals().stream()
                        .filter(item -> containsAny(item, keywords))
                        .findFirst()
                        .map(item -> new FilingEvidence("巨潮公告壁垒线索", item, null, 72)))
                .or(() -> filing.riskSignals().stream()
                        .filter(item -> containsAny(item, keywords))
                        .findFirst()
                        .map(item -> new FilingEvidence("巨潮公告风险线索", item, null, 72)));
    }

    private EvidenceReviewItem verified(
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String searchScope,
            String source,
            String evidenceText,
            String url,
            int confidence,
            String verdict,
            String nextAction
    ) {
        return item(opinion, check, "VERIFIED", "已核实", searchScope, source, evidenceText, url, confidence, verdict, nextAction);
    }

    private EvidenceReviewItem partial(
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String searchScope,
            String source,
            String evidenceText,
            String url,
            int confidence,
            String verdict,
            String nextAction
    ) {
        return item(opinion, check, "PARTIAL", "部分补到", searchScope, source, evidenceText, url, confidence, verdict, nextAction);
    }

    private EvidenceReviewItem notFound(
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String searchScope,
            String evidenceText,
            String nextAction
    ) {
        return item(opinion, check, "NOT_FOUND", "未命中", searchScope, "在线证据源", evidenceText, null, 0,
                "该评分依据当前不存在或待补证", nextAction);
    }

    private EvidenceReviewItem blocked(
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String searchScope,
            String evidenceText,
            String nextAction
    ) {
        return item(opinion, check, "BLOCKED", "源阻塞", searchScope, "数据源缺口", evidenceText, null, 0,
                "当前数据源不足，不能完成复核", nextAction);
    }

    private EvidenceReviewItem item(
            AgentOpinion opinion,
            AgentEvidenceCheck check,
            String reviewStatus,
            String reviewStatusLabel,
            String searchScope,
            String source,
            String evidenceText,
            String url,
            int confidence,
            String verdict,
            String nextAction
    ) {
        return new EvidenceReviewItem(
                opinion.agentCode(),
                opinion.agentName(),
                check.requirement(),
                check.status(),
                check.statusLabel(),
                reviewStatus,
                reviewStatusLabel,
                searchScope,
                source,
                summarize(evidenceText),
                url,
                confidence,
                verdict,
                nextAction
        );
    }

    private int count(List<EvidenceReviewItem> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.reviewStatus())).count();
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

    private List<String> keywordsFrom(String text) {
        return List.of(text.split("[/、，, 和]+")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
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

    private String factorText(CompanyProfile company, String factor) {
        BigDecimal value = company.factors().get(factor);
        return value == null ? "待补充" : value.toString();
    }

    private String valueOrUnknown(BigDecimal value) {
        return value == null ? "待补充" : value.toString();
    }

    private String percentText(BigDecimal value) {
        if (value == null) {
            return "待补充";
        }
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "未找到可用证据";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private record FilingEvidence(String source, String text, String url, int confidence) {
    }

    private record ReviewDecision(String stage, String label) {
    }
}
