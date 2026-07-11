package com.aistock.research.committee;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.evidence.AgentEvidenceCheck;
import com.aistock.research.evidence.AgentEvidenceSearchService;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.DimensionScore;
import com.aistock.research.research.EvidenceTier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentCommitteeService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final CompanyService companyService;
    private final CompanyResearchService companyResearchService;
    private final AgentEvidenceSearchService agentEvidenceSearchService;

    public AgentCommitteeService(
            CompanyService companyService,
            CompanyResearchService companyResearchService,
            AgentEvidenceSearchService agentEvidenceSearchService
    ) {
        this.companyService = companyService;
        this.companyResearchService = companyResearchService;
        this.agentEvidenceSearchService = agentEvidenceSearchService;
    }

    public AgentConsensusReport discuss(String symbol) {
        CompanyProfile company = companyService.getCompany(symbol);
        CompanyResearchView research = companyResearchService.analyze(company);
        List<AgentOpinion> opinions = List.of(
                policyStrategist(research),
                financialAuditor(research),
                moatInvestigator(research),
                valuationDisciplinarian(research),
                riskContrarian(research)
        );
        BigDecimal consensusScore = consensusScore(opinions);
        ConsensusDecision decision = decide(research, opinions, consensusScore);
        return new AgentConsensusReport(
                company.symbol(),
                company.name(),
                decision.stage(),
                decision.label(),
                consensusScore,
                decision.reason(),
                countVote(opinions, "SUPPORT"),
                countVote(opinions, "WATCH"),
                countVote(opinions, "REVIEW"),
                countVote(opinions, "VETO"),
                opinions,
                agreements(opinions),
                disagreements(opinions),
                requiredEvidence(opinions),
                Instant.now()
        );
    }

    private AgentOpinion policyStrategist(CompanyResearchView research) {
        BigDecimal trend = dimension(research, "TREND");
        int policyStrength = evidenceTier(research, "POLICY");
        List<String> supports = new ArrayList<>();
        supports.add("趋势匹配分 " + trend);
        supports.add("政策证据强度 " + policyStrength);
        supports.addAll(research.dimensions().stream()
                .filter(dimension -> "TREND".equals(dimension.code()))
                .flatMap(dimension -> dimension.evidenceRefs().stream())
                .limit(2)
                .toList());
        List<String> objections = new ArrayList<>();
        if (trend.compareTo(new BigDecimal("65")) < 0) {
            objections.add("政策主题仍需主营收入和订单公告验证");
        }
        if (policyStrength < 60) {
            objections.add("政策证据强度不足，可能只是行业关键词命中");
        }
        return opinion(
                research,
                "POLICY_STRATEGIST",
                "政策策略 Agent",
                "判断公司是否真的处在政策主线和产业方向上",
                trend,
                objections,
                supports,
                List.of("主营收入按产品/行业拆分", "政策资金、招投标或订单兑现证据")
        );
    }

    private AgentOpinion financialAuditor(CompanyResearchView research) {
        BigDecimal quality = dimension(research, "QUALITY");
        List<String> supports = List.of("财务质量分 " + quality);
        List<String> objections = new ArrayList<>();
        if (quality.compareTo(new BigDecimal("60")) < 0) {
            objections.add("财务质量不足或年报指标缺失，不适合直接升级观察阶段");
        }
        if (research.company().financialReportDate() == null) {
            objections.add("缺少最近年度年报指标匹配");
        }
        return opinion(
                research,
                "FINANCIAL_AUDITOR",
                "财务质量 Agent",
                "核查 ROE、现金流、毛利率、营收增速和财报完整性",
                quality,
                objections,
                supports,
                List.of("近 10 年 ROE/现金流/毛利率序列", "一次性收益、商誉减值、研发资本化比例")
        );
    }

    private AgentOpinion moatInvestigator(CompanyResearchView research) {
        BigDecimal moat = dimension(research, "MOAT");
        List<String> supports = new ArrayList<>();
        supports.add("核心壁垒分 " + moat);
        supports.add("公告正文事件 " + research.filingEvidence().extractedEvents().size() + " 条");
        research.filingEvidence().extractedEvents().stream()
                .filter(event -> "MOAT".equals(event.eventType()) || "VALIDATION".equals(event.eventType()))
                .map(FilingEvent::evidenceText)
                .limit(3)
                .forEach(supports::add);
        List<String> objections = new ArrayList<>();
        if (research.filingEvidence().moatSignals().isEmpty()) {
            objections.add("公告中暂未识别出核心技术、合同、产能或客户认证线索");
        }
        if (research.filingEvidence().parsedDocuments() == 0) {
            objections.add("尚未解析公告 PDF 正文，壁垒证据停留在标题或画像层");
        }
        return opinion(
                research,
                "MOAT_INVESTIGATOR",
                "公告壁垒 Agent",
                "从公告、年报和正文事件中验证核心资产与业务兑现",
                moat,
                objections,
                supports,
                List.of("核心产品收入占比", "专利/客户认证/产能项目原文", "重大合同后续交付和回款")
        );
    }

    private AgentOpinion valuationDisciplinarian(CompanyResearchView research) {
        BigDecimal valuation = dimension(research, "VALUATION");
        List<String> supports = List.of(
                "估值安全边际分 " + valuation,
                "PE(TTM) " + valueOrUnknown(research.company().peTtm()),
                "PB " + valueOrUnknown(research.company().pbRatio())
        );
        List<String> objections = new ArrayList<>();
        if (valuation.compareTo(new BigDecimal("65")) < 0) {
            objections.add("估值安全边际不足，适合等待价格或盈利兑现");
        }
        if (research.company().peTtm() == null || research.company().peTtm().compareTo(BigDecimal.ZERO) <= 0) {
            objections.add("PE 口径缺失或为负，估值判断需换用 PS/现金流");
        }
        return opinion(
                research,
                "VALUATION_DISCIPLINARIAN",
                "估值纪律 Agent",
                "只关心价格是否给出足够安全边际",
                valuation,
                objections,
                supports,
                List.of("3/5/10 年估值分位", "自由现金流收益率", "同业可比估值")
        );
    }

    private AgentOpinion riskContrarian(CompanyResearchView research) {
        BigDecimal risk = dimension(research, "RISK");
        List<String> supports = new ArrayList<>();
        supports.add("风险排雷分 " + risk);
        if (research.hardBlocks().isEmpty()) {
            supports.add("暂未触发硬性拦截");
        }
        List<String> objections = new ArrayList<>(research.hardBlocks());
        research.filingEvidence().extractedEvents().stream()
                .filter(event -> "RISK".equals(event.eventType()))
                .map(event -> event.documentTitle() + "：" + event.evidenceText())
                .limit(4)
                .forEach(objections::add);
        if (!research.filingEvidence().riskSignals().isEmpty()) {
            objections.add("公告风险线索 " + research.filingEvidence().riskSignals().size() + " 条");
        }
        if (risk.compareTo(new BigDecimal("70")) < 0) {
            objections.add("风险维度低于观察门槛");
        }
        return opinion(
                research,
                "RISK_CONTRARIAN",
                "反方风控 Agent",
                "专门寻找否决条件、反证和尾部风险",
                risk,
                objections,
                supports,
                List.of("监管处罚/问询函/诉讼/质押/减持全文", "审计意见和重大会计差错", "应收账款与商誉减值")
        );
    }

    private AgentOpinion opinion(
            CompanyResearchView research,
            String agentCode,
            String agentName,
            String perspective,
            BigDecimal score,
            List<String> objections,
            List<String> supports,
            List<String> requiredEvidence
    ) {
        List<AgentEvidenceCheck> evidenceChecks = agentEvidenceSearchService.audit(research, agentCode, requiredEvidence);
        List<String> allObjections = new ArrayList<>(objections);
        allObjections.addAll(agentEvidenceSearchService.missingEvidenceObjections(evidenceChecks));
        List<String> distinctObjections = allObjections.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        String vote = vote(score, distinctObjections);
        return new AgentOpinion(
                agentCode,
                agentName,
                perspective,
                vote,
                voteLabel(vote),
                confidence(score, distinctObjections),
                clamp(score),
                supports.stream().filter(item -> item != null && !item.isBlank()).distinct().toList(),
                distinctObjections,
                requiredEvidence,
                evidenceChecks,
                null,
                null,
                null
        );
    }

    private String vote(BigDecimal score, List<String> objections) {
        boolean severe = objections.stream().anyMatch(item -> item.contains("硬性")
                || item.contains("退市")
                || item.contains("立案")
                || item.contains("ST")
                || item.contains("风险维度低于"));
        if (severe || score.compareTo(new BigDecimal("45")) < 0) {
            return "VETO";
        }
        if (score.compareTo(new BigDecimal("60")) < 0 || objections.size() >= 2) {
            return "REVIEW";
        }
        if (score.compareTo(new BigDecimal("72")) < 0 || !objections.isEmpty()) {
            return "WATCH";
        }
        return "SUPPORT";
    }

    private BigDecimal confidence(BigDecimal score, List<String> objections) {
        BigDecimal distance = score.subtract(new BigDecimal("50")).abs()
                .multiply(new BigDecimal("0.65"));
        BigDecimal penalty = BigDecimal.valueOf(Math.min(objections.size() * 3L, 12));
        return clamp(new BigDecimal("55").add(distance).subtract(penalty));
    }

    private BigDecimal consensusScore(List<AgentOpinion> opinions) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        for (AgentOpinion opinion : opinions) {
            BigDecimal confidenceWeight = opinion.confidence().divide(HUNDRED, 4, RoundingMode.HALF_UP);
            BigDecimal voteAdjustment = switch (opinion.vote()) {
                case "SUPPORT" -> new BigDecimal("6.00");
                case "WATCH" -> new BigDecimal("1.50");
                case "REVIEW" -> new BigDecimal("-5.00");
                case "VETO" -> new BigDecimal("-18.00");
                default -> BigDecimal.ZERO;
            };
            total = total.add(opinion.score().add(voteAdjustment).multiply(confidenceWeight));
            weight = weight.add(confidenceWeight);
        }
        if (weight.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return clamp(total.divide(weight, 2, RoundingMode.HALF_UP));
    }

    private ConsensusDecision decide(CompanyResearchView research, List<AgentOpinion> opinions, BigDecimal score) {
        int vetoCount = countVote(opinions, "VETO");
        int supportCount = countVote(opinions, "SUPPORT");
        int reviewCount = countVote(opinions, "REVIEW");
        boolean riskVeto = opinions.stream()
                .anyMatch(opinion -> "RISK_CONTRARIAN".equals(opinion.agentCode()) && "VETO".equals(opinion.vote()));
        if (riskVeto || !research.hardBlocks().isEmpty()) {
            return new ConsensusDecision("RISK_REVIEW", "风险复核", "至少一个 Agent 触发否决或硬性风险，必须先复核反证");
        }
        if (vetoCount > 0) {
            return new ConsensusDecision("EVIDENCE_REVIEW", "证据复核", "至少一个非风险 Agent 暂缓升级，需要先补齐关键证据");
        }
        if (supportCount >= 4 && score.compareTo(new BigDecimal("76")) >= 0) {
            return new ConsensusDecision("VALUATION_WATCH", "估值观察", "多数 Agent 支持且共识分达到观察门槛");
        }
        if (supportCount >= 2 && reviewCount == 0 && score.compareTo(new BigDecimal("64")) >= 0) {
            return new ConsensusDecision("WAIT_FOR_PRICE", "等待价格", "基本面和趋势有共识，但仍需价格或数据兑现");
        }
        if (score.compareTo(new BigDecimal("52")) >= 0) {
            return new ConsensusDecision("EVIDENCE_BUILDING", "证据验证", "Agent 尚未形成强共识，继续补公告、财务和估值证据");
        }
        return new ConsensusDecision("WATCH_SAMPLE", "样本观察", "共识不足，只适合作为样本跟踪");
    }

    private List<String> agreements(List<AgentOpinion> opinions) {
        List<String> agreements = new ArrayList<>();
        if (countVote(opinions, "SUPPORT") + countVote(opinions, "WATCH") >= 3) {
            agreements.add("多数 Agent 认为可以继续跟踪，但阶段需受证据和估值约束");
        }
        if (opinions.stream().noneMatch(opinion -> "VETO".equals(opinion.vote()))) {
            agreements.add("当前未出现跨 Agent 共识型硬否决");
        }
        opinions.stream()
                .flatMap(opinion -> opinion.supports().stream())
                .filter(item -> item.contains("分 "))
                .limit(4)
                .forEach(agreements::add);
        return agreements.stream().distinct().toList();
    }

    private List<String> disagreements(List<AgentOpinion> opinions) {
        return opinions.stream()
                .filter(opinion -> "REVIEW".equals(opinion.vote()) || "VETO".equals(opinion.vote()))
                .flatMap(opinion -> opinion.objections().stream().map(item -> opinion.agentName() + "：" + item))
                .distinct()
                .limit(8)
                .toList();
    }

    private List<String> requiredEvidence(List<AgentOpinion> opinions) {
        Set<String> evidence = new LinkedHashSet<>();
        opinions.stream()
                .filter(opinion -> !"SUPPORT".equals(opinion.vote()))
                .flatMap(opinion -> opinion.requiredEvidence().stream())
                .forEach(evidence::add);
        if (evidence.isEmpty()) {
            opinions.stream()
                    .flatMap(opinion -> opinion.requiredEvidence().stream())
                    .limit(5)
                    .forEach(evidence::add);
        }
        return evidence.stream().limit(10).toList();
    }

    private int countVote(List<AgentOpinion> opinions, String vote) {
        return (int) opinions.stream().filter(opinion -> vote.equals(opinion.vote())).count();
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

    private String voteLabel(String vote) {
        return switch (vote) {
            case "SUPPORT" -> "支持升级";
            case "WATCH" -> "继续观察";
            case "REVIEW" -> "要求复核";
            case "VETO" -> "暂缓/否决";
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

    private record ConsensusDecision(String stage, String label, String reason) {
    }
}
