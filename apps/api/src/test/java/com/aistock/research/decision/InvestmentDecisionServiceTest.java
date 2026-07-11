package com.aistock.research.decision;

import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.committee.AgentOpinion;
import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.filing.FilingDocument;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.filing.FilingEvidenceSummary;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.financial.FinancialHistoryService;
import com.aistock.research.financial.FinancialMetricPoint;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.DimensionScore;
import com.aistock.research.research.EvidenceTier;
import com.aistock.research.review.EvidenceReviewItem;
import com.aistock.research.review.EvidenceReviewReport;
import com.aistock.research.review.EvidenceReviewService;
import com.aistock.research.review.EvidenceReviewStep;
import com.aistock.research.valuation.PeerValuationCompany;
import com.aistock.research.valuation.PeerValuationReport;
import com.aistock.research.valuation.ValuationHistoryReport;
import com.aistock.research.valuation.ValuationHistoryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentDecisionServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final CompanyResearchService companyResearchService = mock(CompanyResearchService.class);
    private final EvidenceReviewService evidenceReviewService = mock(EvidenceReviewService.class);
    private final FinancialHistoryService financialHistoryService = mock(FinancialHistoryService.class);
    private final ValuationHistoryService valuationHistoryService = mock(ValuationHistoryService.class);
    private final InvestmentDecisionService service = new InvestmentDecisionService(
            companyService,
            companyResearchService,
            evidenceReviewService,
            financialHistoryService,
            valuationHistoryService
    );

    @Test
    void shouldBlockInvestmentActionWhenEvidenceSourceIsBlocked() {
        CompanyProfile company = company();
        CompanyResearchView research = research(company, "72");
        AgentConsensusReport consensus = consensus(company, "76", 4, 1, 0, 0);
        EvidenceReviewReport review = review(company, consensus, List.of(blockedItem()), 0, 0, 0, 1);
        when(companyService.getCompany("600900")).thenReturn(company);
        when(companyResearchService.analyze(company)).thenReturn(research);
        when(evidenceReviewService.review("600900")).thenReturn(review);
        when(financialHistoryService.history("600900", 10)).thenReturn(financialHistory(company, 6, "68"));
        when(valuationHistoryService.history("600900", 10)).thenReturn(valuationHistory(company, "0.42", "0.40"));

        InvestmentDecisionReport report = service.evaluate("600900");

        assertThat(report.actionStage()).isEqualTo("EVIDENCE_ONLY");
        assertThat(report.gates()).anySatisfy(gate -> {
            assertThat(gate.gateCode()).isEqualTo("EVIDENCE_REVIEW");
            assertThat(gate.status()).isEqualTo("BLOCK");
        });
        assertThat(report.requiredActions()).anyMatch(action -> action.contains("补证"));
        assertThat(report.complianceNote()).contains("不构成证券投资建议");
    }

    @Test
    void shouldAllowPreBuyCheckOnlyAfterAllMajorGatesPass() {
        CompanyProfile company = company();
        CompanyResearchView research = research(company, "82");
        AgentConsensusReport consensus = consensus(company, "84", 5, 0, 0, 0);
        EvidenceReviewReport review = review(company, consensus, List.of(verifiedItem()), 1, 0, 0, 0);
        when(companyService.getCompany("600900")).thenReturn(company);
        when(companyResearchService.analyze(company)).thenReturn(research);
        when(evidenceReviewService.review("600900")).thenReturn(review);
        when(financialHistoryService.history("600900", 10)).thenReturn(financialHistory(company, 10, "82"));
        when(valuationHistoryService.history("600900", 10)).thenReturn(valuationHistory(company, "0.30", "0.35"));

        InvestmentDecisionReport report = service.evaluate("600900");

        assertThat(report.actionStage()).isEqualTo("PRE_BUY_CHECK");
        assertThat(report.passCount()).isEqualTo(7);
        assertThat(report.exitTriggers()).extracting(ExitTrigger::triggerCode)
                .contains("THESIS_BROKEN", "FINANCIAL_DETERIORATION", "RISK_EVENT");
        assertThat(report.buyPreconditions()).anyMatch(item -> item.contains("人工复读"));
    }

    private EvidenceReviewItem blockedItem() {
        return new EvidenceReviewItem(
                "VALUATION_DISCIPLINARIAN",
                "估值纪律 Agent",
                "自由现金流收益率",
                "MISSING",
                "未找到",
                "BLOCKED",
                "源阻塞",
                "现金流指标",
                "数据源缺口",
                "未找到经营现金流指标，无法复核自由现金流收益率。",
                null,
                0,
                "当前数据源不足，不能完成复核",
                "补齐现金流量表或年报指标历史数据"
        );
    }

    private FinancialHistoryReport financialHistory(CompanyProfile company, int count, String score) {
        return new FinancialHistoryReport(
                company.symbol(),
                company.name(),
                count >= 8 ? "STRONG_SEQUENCE" : "USABLE_SEQUENCE",
                count >= 8 ? "多年质量较强" : "多年序列可用",
                count,
                new BigDecimal(score),
                new BigDecimal("0.14"),
                new BigDecimal("0.42"),
                new BigDecimal("0.08"),
                new BigDecimal("0.10"),
                count,
                0,
                List.of(new FinancialMetricPoint(
                        company.symbol(),
                        company.name(),
                        "2025-12-31",
                        "2025年 年报",
                        new BigDecimal("0.14"),
                        new BigDecimal("2.20"),
                        new BigDecimal("0.42"),
                        new BigDecimal("0.08"),
                        new BigDecimal("0.10"),
                        new BigDecimal("1.30"),
                        new BigDecimal("8.60")
                )),
                List.of("已获取 " + count + " 个年度财务样本"),
                List.of("仍缺资本开支"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private ValuationHistoryReport valuationHistory(CompanyProfile company, String pePercentile, String pbPercentile) {
        return new ValuationHistoryReport(
                company.symbol(),
                company.name(),
                "LOW_PERCENTILE",
                "历史低分位",
                8,
                company.peTtm(),
                company.pbRatio(),
                new BigDecimal(pePercentile),
                new BigDecimal(pbPercentile),
                new BigDecimal("24.00"),
                new BigDecimal("2.60"),
                new BigDecimal("12.00"),
                new BigDecimal("45.00"),
                new BigDecimal("1.40"),
                new BigDecimal("4.20"),
                peerValuation(company),
                List.of(),
                List.of("已按年报 EPS/BPS 与年末收盘价生成 8 个年度估值样本。"),
                List.of("当前为年末估值样本，不是日频或月频 PE/PB 序列。"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private PeerValuationReport peerValuation(CompanyProfile company) {
        return new PeerValuationReport(
                "INDUSTRY",
                "同行业可比",
                3,
                company.peTtm(),
                company.pbRatio(),
                new BigDecimal("20.00"),
                new BigDecimal("2.20"),
                new BigDecimal("22.00"),
                new BigDecimal("2.40"),
                new BigDecimal("0.35"),
                new BigDecimal("0.40"),
                1,
                1,
                List.of(new PeerValuationCompany(
                        "600905",
                        "三峡能源",
                        "电力",
                        "GREEN_POWER",
                        new BigDecimal("18.00"),
                        new BigDecimal("2.10"),
                        new BigDecimal("5.00"),
                        new BigDecimal("800000000"),
                        "同行业"
                )),
                List.of("已基于同行业可比形成 3 个可比样本。"),
                List.of()
        );
    }

    private EvidenceReviewItem verifiedItem() {
        return new EvidenceReviewItem(
                "MOAT_INVESTIGATOR",
                "公告壁垒 Agent",
                "重大合同后续交付和回款",
                "MISSING",
                "未找到",
                "VERIFIED",
                "已复核",
                "巨潮公告正文",
                "公告原文",
                "重大合同进入交付阶段。",
                "https://example.com/detail",
                86,
                "评分依据已被公告线索支持",
                "纳入持有期跟踪"
        );
    }

    private EvidenceReviewReport review(
            CompanyProfile company,
            AgentConsensusReport consensus,
            List<EvidenceReviewItem> items,
            int verified,
            int partial,
            int notFound,
            int blocked
    ) {
        return new EvidenceReviewReport(
                company.symbol(),
                company.name(),
                blocked > 0 ? "SOURCE_BLOCKED" : "CLEAR",
                blocked > 0 ? "数据源阻塞" : "复核通过",
                items.size(),
                verified,
                partial,
                notFound,
                blocked,
                consensus,
                items,
                List.of(new EvidenceReviewStep(
                        "AGENT_REVIEW_GATE",
                        "证据门禁",
                        blocked > 0 ? "存在阻塞项" : "证据项已核实",
                        List.of("公告原文", "年报指标")
                )),
                blocked > 0 ? List.of("数据源阻塞") : List.of("复核通过"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private AgentConsensusReport consensus(
            CompanyProfile company,
            String score,
            int support,
            int watch,
            int review,
            int veto
    ) {
        return new AgentConsensusReport(
                company.symbol(),
                company.name(),
                "VALUATION_WATCH",
                "估值观察",
                new BigDecimal(score),
                "多数 Agent 支持且共识分达到观察门槛",
                support,
                watch,
                review,
                veto,
                List.of(new AgentOpinion(
                        "RISK_CONTRARIAN",
                        "反方风控 Agent",
                        "寻找否决条件",
                        veto > 0 ? "VETO" : "SUPPORT",
                        veto > 0 ? "暂缓/否决" : "支持升级",
                        new BigDecimal("82.00"),
                        new BigDecimal("82.00"),
                        List.of("暂未触发硬性拦截"),
                        List.of(),
                        List.of("监管处罚/问询函/诉讼/质押/减持全文")
                )),
                List.of("多数 Agent 认为可以继续跟踪"),
                List.of(),
                List.of("主营收入拆分", "公告正文核验"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private CompanyResearchView research(CompanyProfile company, String score) {
        FilingDocument document = new FilingDocument(
                "ann-1",
                "关于核心资产和重大合同交付进展的公告",
                "巨潮资讯",
                "业务验证",
                "2026-06-20",
                "https://example.com/detail",
                "https://example.com/file.pdf",
                List.of("核心资产", "重大合同", "交付"),
                86
        );
        FilingEvent event = new FilingEvent(
                "VALIDATION",
                "兑现线索",
                "MEDIUM",
                "ann-1",
                document.title(),
                "公司核心资产保持稳定，重大合同进入交付阶段。",
                document.sourceUrl(),
                88
        );
        FilingEvidenceSummary filing = new FilingEvidenceSummary(
                company.symbol(),
                "LIVE",
                "巨潮实时公告",
                1,
                1,
                List.of(document),
                List.of(event),
                List.of("核心资产保持稳定"),
                List.of(),
                List.of(event.evidenceText()),
                List.of(),
                Instant.parse("2026-06-21T00:00:00Z")
        );
        return new CompanyResearchView(
                company,
                new BigDecimal(score),
                "VALUATION_WATCH",
                "估值观察",
                "多数维度通过，等待人工核验",
                List.of(
                        dimension("TREND", "趋势匹配", score),
                        dimension("QUALITY", "财务质量", score),
                        dimension("MOAT", "核心壁垒", score),
                        dimension("VALUATION", "估值安全边际", score),
                        dimension("RISK", "风险排雷", score)
                ),
                List.of(
                        new EvidenceTier("POLICY", "政策/主题线索", 76, List.of("政策主题持续")),
                        new EvidenceTier("MARKET", "行情与估值证据", 74, List.of("PE/PB 可用")),
                        new EvidenceTier("FILING", "公告/年报证据", 82, List.of(document.title())),
                        new EvidenceTier("VALIDATION", "订单/招投标/财务兑现", 78, List.of(event.evidenceText()))
                ),
                List.of(),
                List.of("人工复读最新公告"),
                List.of(),
                List.of("巨潮资讯", "东方财富指标"),
                filing,
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private DimensionScore dimension(String code, String name, String score) {
        return new DimensionScore(
                code,
                name,
                new BigDecimal(score),
                name + "达到观察要求",
                List.of(name + "证据"),
                List.of(name + "复核")
        );
    }

    private CompanyProfile company() {
        return new CompanyProfile(
                "600900",
                "长江电力",
                "上交所",
                "电力",
                "GREEN_POWER",
                new BigDecimal("82.00"),
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                new BigDecimal("22.00"),
                new BigDecimal("3.00"),
                BigDecimal.ONE,
                new BigDecimal("1200000000"),
                "https://quote.example.com/600900",
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("水电核心资产", "稳定现金流"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of()
        );
    }
}
