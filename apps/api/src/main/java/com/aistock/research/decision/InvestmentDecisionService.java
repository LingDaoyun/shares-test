package com.aistock.research.decision;

import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.financial.FinancialHistoryService;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.DimensionScore;
import com.aistock.research.research.EvidenceTier;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.review.EvidenceReviewItem;
import com.aistock.research.review.EvidenceReviewReport;
import com.aistock.research.review.EvidenceReviewService;
import com.aistock.research.valuation.PeerValuationReport;
import com.aistock.research.valuation.ValuationHistoryReport;
import com.aistock.research.valuation.ValuationHistoryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InvestmentDecisionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final CompanyService companyService;
    private final CompanyResearchService companyResearchService;
    private final EvidenceReviewService evidenceReviewService;
    private final FinancialHistoryService financialHistoryService;
    private final ValuationHistoryService valuationHistoryService;

    public InvestmentDecisionService(
            CompanyService companyService,
            CompanyResearchService companyResearchService,
            EvidenceReviewService evidenceReviewService,
            FinancialHistoryService financialHistoryService,
            ValuationHistoryService valuationHistoryService
    ) {
        this.companyService = companyService;
        this.companyResearchService = companyResearchService;
        this.evidenceReviewService = evidenceReviewService;
        this.financialHistoryService = financialHistoryService;
        this.valuationHistoryService = valuationHistoryService;
    }

    public InvestmentDecisionReport evaluate(String symbol) {
        CompanyProfile company = companyService.getCompany(symbol);
        CompanyResearchView research = companyResearchService.analyze(company);
        FinancialHistoryReport financialHistory = financialHistoryService.history(symbol, 10);
        ValuationHistoryReport valuationHistory = valuationHistoryService.history(symbol, 10);
        EvidenceReviewReport evidenceReview = evidenceReviewService.review(symbol);
        AgentConsensusReport consensus = evidenceReview.consensus();
        List<InvestmentDecisionGate> gates = List.of(
                policyHypothesisGate(research),
                businessExposureGate(research),
                financialQualityGate(research, financialHistory),
                evidenceReviewGate(evidenceReview),
                riskVetoGate(research, consensus),
                valuationDisciplineGate(research, valuationHistory),
                portfolioDisciplineGate(research)
        );
        Decision decision = decide(research, consensus, evidenceReview, gates);
        return new InvestmentDecisionReport(
                company.symbol(),
                company.name(),
                decision.stage(),
                decision.label(),
                decision.score(),
                decision.reason(),
                "仅用于长线投研观察与证据管理，不构成证券投资建议或自动交易指令。",
                count(gates, "PASS"),
                count(gates, "WATCH"),
                count(gates, "BLOCK"),
                count(gates, "FAIL"),
                gates,
                thesis(research, consensus),
                buyPreconditions(gates, evidenceReview, consensus),
                holdDisciplines(research),
                exitTriggers(research),
                requiredActions(gates, evidenceReview, consensus),
                financialHistory,
                valuationHistory,
                consensus,
                evidenceReview,
                Instant.now()
        );
    }

    private InvestmentDecisionGate policyHypothesisGate(CompanyResearchView research) {
        BigDecimal trend = dimension(research, "TREND");
        int policyStrength = evidenceTier(research, "POLICY");
        List<String> refs = evidenceRefs(research, "TREND", "POLICY");
        if (trend.compareTo(new BigDecimal("70")) >= 0 && policyStrength >= 65) {
            return gate("POLICY_HYPOTHESIS", "政策假设", "PASS", new BigDecimal("8.00"),
                    "政策与产业方向较强，但仍只作为研究假设来源。", refs);
        }
        if (trend.compareTo(new BigDecimal("55")) >= 0 || policyStrength >= 50) {
            return gate("POLICY_HYPOTHESIS", "政策假设", "WATCH", BigDecimal.ZERO,
                    "政策线索可跟踪，但不能单独支撑投资动作。", refs);
        }
        return gate("POLICY_HYPOTHESIS", "政策假设", "BLOCK", new BigDecimal("-8.00"),
                "政策或产业方向证据不足，先留在样本池。", refs);
    }

    private InvestmentDecisionGate businessExposureGate(CompanyResearchView research) {
        BigDecimal moat = dimension(research, "MOAT");
        int validationStrength = evidenceTier(research, "VALIDATION");
        int moatSignals = research.filingEvidence().moatSignals().size();
        int validationSignals = research.filingEvidence().validationSignals().size();
        List<String> refs = new ArrayList<>(evidenceRefs(research, "MOAT", "VALIDATION"));
        research.filingEvidence().extractedEvents().stream()
                .filter(event -> "MOAT".equals(event.eventType()) || "VALIDATION".equals(event.eventType()))
                .map(FilingEvent::evidenceText)
                .limit(3)
                .forEach(refs::add);
        if (moat.compareTo(new BigDecimal("68")) >= 0 && (moatSignals + validationSignals) > 0 && validationStrength >= 60) {
            return gate("BUSINESS_EXPOSURE", "真实受益验证", "PASS", new BigDecimal("10.00"),
                    "已有公告或画像线索支持公司可能真实受益。", refs);
        }
        if (moat.compareTo(new BigDecimal("55")) >= 0 || (moatSignals + validationSignals) > 0) {
            return gate("BUSINESS_EXPOSURE", "真实受益验证", "WATCH", BigDecimal.ZERO,
                    "公司具备业务关联线索，但主营占比、订单兑现仍需补证。", refs);
        }
        return gate("BUSINESS_EXPOSURE", "真实受益验证", "BLOCK", new BigDecimal("-10.00"),
                "暂未证明公司是真受益，而不只是概念标签命中。", refs);
    }

    private InvestmentDecisionGate financialQualityGate(CompanyResearchView research, FinancialHistoryReport history) {
        BigDecimal quality = history.annualPointCount() > 0 ? history.qualityScore() : dimension(research, "QUALITY");
        List<String> refs = evidenceRefs(research, "QUALITY", "MARKET");
        List<String> evidenceRefs = new ArrayList<>(refs);
        evidenceRefs.addAll(history.conclusions());
        if (history.annualPointCount() >= 8 && quality.compareTo(new BigDecimal("72")) >= 0) {
            return gate("FINANCIAL_QUALITY", "财务质量", "PASS", new BigDecimal("12.00"),
                    "已补齐多年财务序列，财务质量达到长线观察要求。", evidenceRefs);
        }
        if (history.annualPointCount() >= 5 && quality.compareTo(new BigDecimal("58")) >= 0) {
            return gate("FINANCIAL_QUALITY", "财务质量", "WATCH", new BigDecimal("2.00"),
                    "多年财务序列可用，但仍需补会计质量、自由现金流和主营拆分。", evidenceRefs);
        }
        if (research.company().financialReportDate() == null || research.company().financialReportDate().isBlank()) {
            return gate("FINANCIAL_QUALITY", "财务质量", "BLOCK", new BigDecimal("-12.00"),
                    "缺少足够年度财务序列，长线判断不能越过财务质量门槛。", evidenceRefs);
        }
        if (quality.compareTo(new BigDecimal("68")) >= 0) {
            return gate("FINANCIAL_QUALITY", "财务质量", "PASS", new BigDecimal("10.00"),
                    "财务质量分达到长线观察要求。", evidenceRefs);
        }
        if (quality.compareTo(new BigDecimal("56")) >= 0) {
            return gate("FINANCIAL_QUALITY", "财务质量", "WATCH", BigDecimal.ZERO,
                    "财务质量可观察，但需要拉长到多年 ROE、现金流、毛利率序列。", evidenceRefs);
        }
        return gate("FINANCIAL_QUALITY", "财务质量", "BLOCK", new BigDecimal("-12.00"),
                "财务质量未达长线门槛，先排除交易动作。", evidenceRefs);
    }

    private InvestmentDecisionGate evidenceReviewGate(EvidenceReviewReport review) {
        List<String> refs = review.conclusions();
        if (review.totalItems() == 0) {
            return gate("EVIDENCE_REVIEW", "证据复核", "WATCH", BigDecimal.ZERO,
                    "当前没有待复核项，但仍需保留人工抽查。", refs);
        }
        if (review.blockedCount() > 0) {
            return gate("EVIDENCE_REVIEW", "证据复核", "BLOCK", new BigDecimal("-14.00"),
                    "存在数据源阻塞，不能升级为投资动作。", refs);
        }
        if (review.notFoundCount() > 2) {
            return gate("EVIDENCE_REVIEW", "证据复核", "BLOCK", new BigDecimal("-10.00"),
                    "多个 Agent 评分依据未命中，说明结论还停留在假设层。", refs);
        }
        if (review.notFoundCount() > 0 || review.partialCount() > 0) {
            return gate("EVIDENCE_REVIEW", "证据复核", "WATCH", new BigDecimal("-2.00"),
                    "证据链部分补齐，继续人工阅读原文或补历史序列。", refs);
        }
        return gate("EVIDENCE_REVIEW", "证据复核", "PASS", new BigDecimal("12.00"),
                "Agent 缺口均已复核通过。", refs);
    }

    private InvestmentDecisionGate riskVetoGate(CompanyResearchView research, AgentConsensusReport consensus) {
        List<String> refs = new ArrayList<>(research.hardBlocks());
        consensus.opinions().stream()
                .filter(opinion -> "RISK_CONTRARIAN".equals(opinion.agentCode()))
                .flatMap(opinion -> opinion.objections().stream())
                .limit(4)
                .forEach(refs::add);
        boolean riskVeto = consensus.opinions().stream()
                .anyMatch(opinion -> "RISK_CONTRARIAN".equals(opinion.agentCode()) && "VETO".equals(opinion.vote()));
        if (!research.hardBlocks().isEmpty()) {
            return gate("RISK_VETO", "反方风控", "FAIL", new BigDecimal("-25.00"),
                    "硬性风险已触发，先排雷，不进入买入前核验。", refs);
        }
        if (riskVeto || consensus.vetoCount() > 0) {
            return gate("RISK_VETO", "反方风控", "BLOCK", new BigDecimal("-16.00"),
                    "存在 Agent 否决意见，必须先处理反证。", refs);
        }
        if (consensus.reviewCount() > 0) {
            return gate("RISK_VETO", "反方风控", "WATCH", new BigDecimal("-2.00"),
                    "没有硬否决，但仍有复核票需要处理。", refs);
        }
        return gate("RISK_VETO", "反方风控", "PASS", new BigDecimal("8.00"),
                "当前未出现硬性风险或否决共识。", refs);
    }

    private InvestmentDecisionGate valuationDisciplineGate(CompanyResearchView research, ValuationHistoryReport valuationHistory) {
        BigDecimal valuation = dimension(research, "VALUATION");
        List<String> refs = evidenceRefs(research, "VALUATION", "MARKET");
        List<String> evidenceRefs = new ArrayList<>(refs);
        evidenceRefs.addAll(valuationHistory.conclusions());
        if (valuationHistory.sampleCount() >= 5) {
            BigDecimal pePercentile = valuationHistory.pePercentile();
            BigDecimal pbPercentile = valuationHistory.pbPercentile();
            boolean lowPe = pePercentile != null && pePercentile.compareTo(new BigDecimal("0.35")) <= 0;
            boolean lowPb = pbPercentile != null && pbPercentile.compareTo(new BigDecimal("0.45")) <= 0;
            boolean highPe = pePercentile != null && pePercentile.compareTo(new BigDecimal("0.80")) >= 0;
            boolean highPb = pbPercentile != null && pbPercentile.compareTo(new BigDecimal("0.80")) >= 0;
            PeerValuationReport peerValuation = valuationHistory.peerValuation();
            boolean peerLow = peerValuation.peerCount() >= 3
                    && ((peerValuation.pePeerPercentile() != null && peerValuation.pePeerPercentile().compareTo(new BigDecimal("0.35")) <= 0)
                    || (peerValuation.pbPeerPercentile() != null && peerValuation.pbPeerPercentile().compareTo(new BigDecimal("0.35")) <= 0));
            boolean peerHigh = peerValuation.peerCount() >= 3
                    && peerValuation.pePeerPercentile() != null
                    && peerValuation.pbPeerPercentile() != null
                    && peerValuation.pePeerPercentile().compareTo(new BigDecimal("0.80")) >= 0
                    && peerValuation.pbPeerPercentile().compareTo(new BigDecimal("0.80")) >= 0;
            if ((lowPe || lowPb || peerLow) && !peerHigh) {
                return gate("VALUATION_DISCIPLINE", "估值纪律", "PASS", new BigDecimal("12.00"),
                        "历史或同业估值分位处于低位或合理区间，安全边际可进入人工复核。", evidenceRefs);
            }
            if ((highPe && highPb) || peerHigh) {
                return gate("VALUATION_DISCIPLINE", "估值纪律", "BLOCK", new BigDecimal("-14.00"),
                        "PE 与 PB 同时处于历史或同业高分位，不能用好故事覆盖高价格。", evidenceRefs);
            }
            return gate("VALUATION_DISCIPLINE", "估值纪律", "WATCH", new BigDecimal("2.00"),
                    "历史和同业估值未显示明显便宜，适合等待价格或盈利继续兑现。", evidenceRefs);
        }
        if (research.company().peTtm() == null || research.company().peTtm().compareTo(BigDecimal.ZERO) <= 0) {
            return gate("VALUATION_DISCIPLINE", "估值纪律", "WATCH", new BigDecimal("-3.00"),
                    "PE 缺失或为负，需要换用现金流、PS 或分部估值。", evidenceRefs);
        }
        if (valuation.compareTo(new BigDecimal("70")) >= 0) {
            return gate("VALUATION_DISCIPLINE", "估值纪律", "PASS", new BigDecimal("10.00"),
                    "估值安全边际分达到观察要求。", evidenceRefs);
        }
        if (valuation.compareTo(new BigDecimal("55")) >= 0) {
            return gate("VALUATION_DISCIPLINE", "估值纪律", "WATCH", BigDecimal.ZERO,
                    "价格没有明显便宜，需要等待更好的价格或盈利兑现。", evidenceRefs);
        }
        return gate("VALUATION_DISCIPLINE", "估值纪律", "BLOCK", new BigDecimal("-12.00"),
                "估值纪律不通过，不能用好故事覆盖高价格。", evidenceRefs);
    }

    private InvestmentDecisionGate portfolioDisciplineGate(CompanyResearchView research) {
        CompanyProfile company = research.company();
        List<String> refs = List.of(
                "市场：" + valueOrUnknown(company.market()),
                "行业：" + valueOrUnknown(company.industry()),
                "成交额：" + valueOrUnknown(company.amount()),
                "行情源：" + valueOrUnknown(company.dataSource())
        );
        if (!company.liveData()) {
            return gate("PORTFOLIO_DISCIPLINE", "组合纪律", "WATCH", new BigDecimal("-2.00"),
                    "行情不是实时源，仓位和价格判断需要复核。", refs);
        }
        if (!RecommendationQuality.hasSufficientLiquidity(company.amount())) {
            return gate("PORTFOLIO_DISCIPLINE", "组合纪律", "WATCH", new BigDecimal("-2.00"),
                    RecommendationQuality.liquidityRiskText(), refs);
        }
        return gate("PORTFOLIO_DISCIPLINE", "组合纪律", "PASS", new BigDecimal("4.00"),
                "行情与流动性满足观察池跟踪要求。", refs);
    }

    private Decision decide(
            CompanyResearchView research,
            AgentConsensusReport consensus,
            EvidenceReviewReport review,
            List<InvestmentDecisionGate> gates
    ) {
        BigDecimal score = decisionScore(research, consensus, review, gates);
        int failCount = count(gates, "FAIL");
        int blockCount = count(gates, "BLOCK");
        int passCount = count(gates, "PASS");
        boolean valuationWatch = gates.stream()
                .anyMatch(gate -> "VALUATION_DISCIPLINE".equals(gate.gateCode()) && "WATCH".equals(gate.status()));
        if (failCount > 0) {
            return new Decision("RISK_BLOCKED", "风险阻断", score, "硬风险未处理前，只能做排雷复核。");
        }
        if (blockCount > 0) {
            return new Decision("EVIDENCE_ONLY", "只观察不买入", score, "关键证据或估值门禁未通过，先补证再升级。");
        }
        if (valuationWatch) {
            return new Decision("WAIT_FOR_PRICE", "等待价格", score, "基本逻辑可跟踪，但价格或估值安全边际还不够。");
        }
        if (passCount >= 6 && score.compareTo(new BigDecimal("78")) >= 0) {
            return new Decision("PRE_BUY_CHECK", "买入前核验", score, "门禁大多通过，可进入人工核验与仓位讨论。");
        }
        if (score.compareTo(new BigDecimal("64")) >= 0) {
            return new Decision("LONG_WATCH", "长线观察", score, "具备持续跟踪价值，但还没到动作核验阶段。");
        }
        return new Decision("SAMPLE_TRACKING", "样本跟踪", score, "结论较弱，保留为样本，不进入核心观察。");
    }

    private BigDecimal decisionScore(
            CompanyResearchView research,
            AgentConsensusReport consensus,
            EvidenceReviewReport review,
            List<InvestmentDecisionGate> gates
    ) {
        BigDecimal evidenceScore = evidenceScore(review);
        BigDecimal gateImpact = gates.stream()
                .map(InvestmentDecisionGate::scoreImpact)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = consensus.consensusScore().multiply(new BigDecimal("0.42"))
                .add(research.overallScore().multiply(new BigDecimal("0.32")))
                .add(evidenceScore.multiply(new BigDecimal("0.26")))
                .add(gateImpact);
        return clamp(score);
    }

    private BigDecimal evidenceScore(EvidenceReviewReport review) {
        if (review.totalItems() == 0) {
            return new BigDecimal("58.00");
        }
        BigDecimal score = BigDecimal.valueOf(review.verifiedCount()).multiply(new BigDecimal("100"))
                .add(BigDecimal.valueOf(review.partialCount()).multiply(new BigDecimal("58")))
                .add(BigDecimal.valueOf(review.notFoundCount()).multiply(new BigDecimal("18")))
                .subtract(BigDecimal.valueOf(review.blockedCount()).multiply(new BigDecimal("20")));
        return clamp(score.divide(BigDecimal.valueOf(review.totalItems()), 2, RoundingMode.HALF_UP));
    }

    private List<String> thesis(CompanyResearchView research, AgentConsensusReport consensus) {
        Set<String> items = new LinkedHashSet<>();
        items.add("当前阶段：" + research.stageLabel() + "，" + research.stageReason());
        if (!consensus.consensusReason().isBlank()) {
            items.add("Agent 共识：" + consensus.consensusReason());
        }
        consensus.agreements().stream().limit(3).forEach(items::add);
        research.dimensions().stream()
                .filter(dimension -> dimension.score().compareTo(new BigDecimal("65")) >= 0)
                .map(dimension -> dimension.name() + "：" + dimension.verdict())
                .limit(3)
                .forEach(items::add);
        return items.stream().limit(8).toList();
    }

    private List<String> buyPreconditions(
            List<InvestmentDecisionGate> gates,
            EvidenceReviewReport review,
            AgentConsensusReport consensus
    ) {
        Set<String> items = new LinkedHashSet<>();
        gates.stream()
                .filter(gate -> !"PASS".equals(gate.status()))
                .map(gate -> gate.gateName() + "：" + gate.conclusion())
                .forEach(items::add);
        review.items().stream()
                .filter(item -> !"VERIFIED".equals(item.reviewStatus()))
                .map(EvidenceReviewItem::nextAction)
                .filter(item -> item != null && !item.isBlank())
                .limit(5)
                .forEach(items::add);
        boolean hasOpenGate = gates.stream().anyMatch(gate -> !"PASS".equals(gate.status()));
        boolean hasOpenReview = review.blockedCount() > 0 || review.notFoundCount() > 0 || review.partialCount() > 0;
        if (hasOpenGate || hasOpenReview) {
            consensus.requiredEvidence().stream().limit(5).forEach(item -> items.add("补齐：" + item));
        }
        if (items.isEmpty()) {
            items.add("人工复读最新年报、公告原文和估值假设后，再讨论仓位。");
        }
        return items.stream().limit(10).toList();
    }

    private List<String> holdDisciplines(CompanyResearchView research) {
        List<String> items = new ArrayList<>();
        items.add("每次季报/年报披露后重跑财务质量、公告证据和 Agent 共识。");
        items.add("每月至少复核一次政策假设是否仍有财政、订单或产业兑现。");
        items.add("价格快速上涨后优先重算估值安全边际，而不是追随主题热度。");
        items.add("组合层面限制单一行业与单一政策主题暴露，避免相关性过高。");
        if (!research.dataGaps().isEmpty()) {
            items.add("当前数据缺口：" + research.dataGaps().stream().limit(3).collect(Collectors.joining(" / ")));
        }
        return items;
    }

    private List<ExitTrigger> exitTriggers(CompanyResearchView research) {
        List<ExitTrigger> triggers = new ArrayList<>();
        if (!research.filingEvidence().riskSignals().isEmpty()) {
            triggers.add(new ExitTrigger(
                    "CURRENT_RISK_SIGNAL",
                    "公告风险线索",
                    "HIGH",
                    "公告风险线索已经出现，需要优先排查。",
                    "暂停升级，人工阅读相关公告原文。",
                    research.filingEvidence().riskSignals().stream().limit(4).toList()
            ));
        }
        if (research.company().peTtm() != null && research.company().peTtm().compareTo(new BigDecimal("80")) > 0) {
            triggers.add(new ExitTrigger(
                    "VALUATION_OVERHEAT",
                    "估值过热",
                    "MEDIUM",
                    "PE(TTM) 高于 80，市场预期可能已经较满。",
                    "除非盈利兑现显著超预期，否则降级为等待价格。",
                    List.of("PE(TTM) " + research.company().peTtm())
            ));
        }
        triggers.add(new ExitTrigger(
                "THESIS_BROKEN",
                "投资假设破坏",
                "HIGH",
                "政策主线、订单兑现或主营收入占比连续两个复核周期未得到验证。",
                "从核心观察降级到样本池，重新建立假设。",
                List.of("政策证据", "主营收入拆分", "订单/招投标兑现")
        ));
        triggers.add(new ExitTrigger(
                "FINANCIAL_DETERIORATION",
                "财务质量恶化",
                "HIGH",
                "ROE、经营现金流、毛利率或应收账款质量出现连续恶化。",
                "重新跑财务质量 Agent，必要时触发风险复核。",
                List.of("ROE 序列", "经营现金流", "毛利率", "应收账款")
        ));
        triggers.add(new ExitTrigger(
                "RISK_EVENT",
                "重大风险事件",
                "HIGH",
                "监管处罚、问询函、诉讼、质押、减持、审计保留意见等风险出现。",
                "触发反方风控 Agent 否决流程。",
                List.of("交易所公告", "巨潮公告", "监管处罚信息")
        ));
        return triggers;
    }

    private List<String> requiredActions(
            List<InvestmentDecisionGate> gates,
            EvidenceReviewReport review,
            AgentConsensusReport consensus
    ) {
        Set<String> actions = new LinkedHashSet<>();
        gates.stream()
                .filter(gate -> "BLOCK".equals(gate.status()) || "FAIL".equals(gate.status()))
                .map(gate -> "处理门禁：" + gate.gateName())
                .forEach(actions::add);
        review.items().stream()
                .filter(item -> "BLOCKED".equals(item.reviewStatus()) || "NOT_FOUND".equals(item.reviewStatus()))
                .map(item -> item.agentName() + "补证：" + item.requirement())
                .limit(6)
                .forEach(actions::add);
        consensus.disagreements().stream().limit(4).map(item -> "复核分歧：" + item).forEach(actions::add);
        if (actions.isEmpty()) {
            actions.add("进入人工核验清单：年报原文、公告原文、估值假设、仓位上限。");
        }
        return actions.stream().limit(10).toList();
    }

    private InvestmentDecisionGate gate(
            String code,
            String name,
            String status,
            BigDecimal scoreImpact,
            String conclusion,
            List<String> evidenceRefs
    ) {
        return new InvestmentDecisionGate(
                code,
                name,
                status,
                statusLabel(status),
                scoreImpact.setScale(2, RoundingMode.HALF_UP),
                conclusion,
                evidenceRefs.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .distinct()
                        .limit(6)
                        .toList()
        );
    }

    private List<String> evidenceRefs(CompanyResearchView research, String dimensionCode, String tierCode) {
        List<String> refs = new ArrayList<>();
        research.dimensions().stream()
                .filter(dimension -> dimensionCode.equals(dimension.code()))
                .flatMap(dimension -> dimension.evidenceRefs().stream())
                .forEach(refs::add);
        research.evidenceTiers().stream()
                .filter(tier -> tierCode.equals(tier.code()))
                .flatMap(tier -> tier.evidenceRefs().stream())
                .forEach(refs::add);
        return refs;
    }

    private BigDecimal dimension(CompanyResearchView research, String code) {
        return research.dimensions().stream()
                .filter(dimension -> code.equals(dimension.code()))
                .map(DimensionScore::score)
                .findFirst()
                .orElse(ZERO);
    }

    private int evidenceTier(CompanyResearchView research, String code) {
        Map<String, EvidenceTier> tiers = research.evidenceTiers().stream()
                .collect(Collectors.toMap(EvidenceTier::code, Function.identity()));
        EvidenceTier tier = tiers.get(code);
        return tier == null ? 0 : tier.strength();
    }

    private int count(List<InvestmentDecisionGate> gates, String status) {
        return (int) gates.stream().filter(gate -> status.equals(gate.status())).count();
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "PASS" -> "通过";
            case "WATCH" -> "观察";
            case "BLOCK" -> "阻断";
            case "FAIL" -> "失败";
            default -> "未知";
        };
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "待补充" : value.toString();
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return ZERO;
        }
        if (value.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Decision(String stage, String label, BigDecimal score, String reason) {
    }
}
