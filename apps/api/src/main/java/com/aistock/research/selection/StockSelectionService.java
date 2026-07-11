package com.aistock.research.selection;

import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.committee.AgentCommitteeService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class StockSelectionService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int DEFAULT_REVIEW_LIMIT = 18;

    private final CompanyService companyService;
    private final AgentCommitteeService agentCommitteeService;

    public StockSelectionService(CompanyService companyService, AgentCommitteeService agentCommitteeService) {
        this.companyService = companyService;
        this.agentCommitteeService = agentCommitteeService;
    }

    public StockSelectionReport shortlist(Integer limit, Integer reviewLimit) {
        int safeLimit = clamp(limit, DEFAULT_LIMIT, 1, 10);
        int safeReviewLimit = clamp(reviewLimit, DEFAULT_REVIEW_LIMIT, safeLimit, 40);
        List<CompanyProfile> universe = companyService.listCompanies();
        Map<String, CompanyProfile> bySymbol = universe.stream()
                .collect(Collectors.toMap(CompanyProfile::symbol, Function.identity(), (left, right) -> left));

        List<CandidateScore> reviewed = universe.stream()
                .limit(safeReviewLimit)
                .map(company -> score(company, agentCommitteeService.discuss(company.symbol())))
                .sorted(Comparator.comparing(CandidateScore::finalScore).reversed()
                        .thenComparing(candidate -> candidate.report().vetoCount())
                        .thenComparing(candidate -> candidate.report().reviewCount()))
                .toList();

        List<CandidateScore> finalists = reviewed.stream()
                .limit(safeLimit)
                .toList();
        List<StockSelectionCandidate> selected = IntStream.range(0, finalists.size())
                .mapToObj(index -> {
                    CandidateScore candidate = finalists.get(index);
                    return toCandidate(index + 1, candidate, bySymbol.get(candidate.report().symbol()));
                })
                .toList();

        return new StockSelectionReport(
                "全市场公司池",
                universe.size(),
                reviewed.size(),
                selected.size(),
                List.of(
                        "全市场公司池不按代码前缀过滤",
                        "每只候选股由政策策略、财务质量、公告壁垒、估值纪律、反方风控五个 Agent 投票",
                        "最终排序综合共识分、支持/观察票、复核/否决票和阶段安全性",
                        "入选只代表进入长线投研观察，不构成交易建议"
                ),
                selected,
                Instant.now()
        );
    }

    private CandidateScore score(CompanyProfile company, AgentConsensusReport report) {
        BigDecimal score = report.consensusScore()
                .add(BigDecimal.valueOf(report.supportCount()).multiply(new BigDecimal("3.50")))
                .add(BigDecimal.valueOf(report.watchCount()).multiply(new BigDecimal("1.20")))
                .subtract(BigDecimal.valueOf(report.reviewCount()).multiply(new BigDecimal("2.80")))
                .subtract(BigDecimal.valueOf(report.vetoCount()).multiply(new BigDecimal("9.00")))
                .add(stageBonus(report.consensusStage()))
                .add(marketEvidenceBonus(company));
        return new CandidateScore(report, clamp(score));
    }

    private StockSelectionCandidate toCandidate(int rank, CandidateScore candidate, CompanyProfile company) {
        AgentConsensusReport report = candidate.report();
        String label = label(report);
        return new StockSelectionCandidate(
                rank,
                report.symbol(),
                report.companyName(),
                valueOrUnknown(company == null ? null : company.market()),
                valueOrUnknown(company == null ? null : company.industry()),
                candidate.finalScore(),
                label,
                reason(report, label),
                report,
                trace(report, company, label)
        );
    }

    private List<StockSelectionTraceStep> trace(AgentConsensusReport report, CompanyProfile company, String label) {
        return List.of(
                new StockSelectionTraceStep(
                        "UNIVERSE_SCREEN",
                        "全市场候选筛选器",
                        "未按股票代码前缀过滤，从当前实时公司池进入候选复核。",
                        List.of(
                                "市场：" + valueOrUnknown(company == null ? null : company.market()),
                                "行业：" + valueOrUnknown(company == null ? null : company.industry()),
                                "数据源：" + valueOrUnknown(company == null ? null : company.dataSource())
                        )
                ),
                new StockSelectionTraceStep(
                        "AGENT_DISCUSSION",
                        "五 Agent 投研委员会",
                        "共识阶段为 " + report.consensusLabel() + "，共识分 " + report.consensusScore() + "。",
                        List.of(
                                "支持 " + report.supportCount(),
                                "观察 " + report.watchCount(),
                                "复核 " + report.reviewCount(),
                                "否决 " + report.vetoCount()
                        )
                ),
                new StockSelectionTraceStep(
                        "DISAGREEMENT_REVIEW",
                        "反证与分歧复核",
                        report.disagreements().isEmpty() ? "当前没有突出的跨 Agent 分歧。" : "存在需要继续补证的分歧。",
                        report.disagreements().stream().limit(4).toList()
                ),
                new StockSelectionTraceStep(
                        "FINAL_SHORTLIST",
                        "候选池裁判",
                        label + "：" + reason(report, label),
                        report.requiredEvidence().stream().limit(5).toList()
                )
        );
    }

    private String label(AgentConsensusReport report) {
        if (report.vetoCount() > 0) {
            return "证据复核";
        }
        if (report.supportCount() >= 3 && report.consensusScore().compareTo(new BigDecimal("70")) >= 0) {
            return "优先观察";
        }
        if (report.supportCount() + report.watchCount() >= 4) {
            return "继续跟踪";
        }
        return "样本观察";
    }

    private String reason(AgentConsensusReport report, String label) {
        if ("优先观察".equals(label)) {
            return "多数 Agent 支持或观察，且共识分达到优先观察区间。";
        }
        if ("继续跟踪".equals(label)) {
            return "未形成强买入式结论，但多数 Agent 认可继续跟踪价值。";
        }
        if ("证据复核".equals(label)) {
            return "存在复核或否决意见，适合先补证再决定是否升级。";
        }
        return "共识尚弱，只适合作为样本持续观察。";
    }

    private BigDecimal stageBonus(String stage) {
        return switch (stage) {
            case "VALUATION_WATCH" -> new BigDecimal("6.00");
            case "WAIT_FOR_PRICE" -> new BigDecimal("4.00");
            case "EVIDENCE_BUILDING" -> new BigDecimal("1.50");
            case "RISK_REVIEW" -> new BigDecimal("-10.00");
            case "EVIDENCE_REVIEW" -> new BigDecimal("-4.00");
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal marketEvidenceBonus(CompanyProfile company) {
        if (company == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal bonus = BigDecimal.ZERO;
        if (company.liveData()) {
            bonus = bonus.add(new BigDecimal("1.00"));
        }
        if (company.financialReportDate() != null && !company.financialReportDate().isBlank()) {
            bonus = bonus.add(new BigDecimal("2.00"));
        }
        return bonus;
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.compareTo(new BigDecimal("100.00")) > 0) {
            return new BigDecimal("100.00");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "待补充" : value.toString();
    }

    private record CandidateScore(AgentConsensusReport report, BigDecimal finalScore) {
    }
}
