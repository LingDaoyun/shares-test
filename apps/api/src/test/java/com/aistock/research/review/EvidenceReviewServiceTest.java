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
import com.aistock.research.financial.FinancialMetricPoint;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.DimensionScore;
import com.aistock.research.research.EvidenceTier;
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

class EvidenceReviewServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final CompanyResearchService companyResearchService = mock(CompanyResearchService.class);
    private final AgentCommitteeService agentCommitteeService = mock(AgentCommitteeService.class);
    private final FinancialHistoryService financialHistoryService = mock(FinancialHistoryService.class);
    private final ValuationHistoryService valuationHistoryService = mock(ValuationHistoryService.class);
    private final EvidenceReviewService service = new EvidenceReviewService(
            companyService,
            companyResearchService,
            agentCommitteeService,
            financialHistoryService,
            valuationHistoryService
    );

    @Test
    void shouldRunEvidenceReviewChainForAgentGaps() {
        CompanyProfile company = company();
        CompanyResearchView research = research(company);
        when(companyService.getCompany("000977")).thenReturn(company);
        when(companyResearchService.analyze(company)).thenReturn(research);
        when(agentCommitteeService.discuss("000977")).thenReturn(consensus(company));
        when(financialHistoryService.history("000977", 10)).thenReturn(financialHistory(company));
        when(valuationHistoryService.history("000977", 10)).thenReturn(valuationHistory(company));

        EvidenceReviewReport report = service.review("000977");

        assertThat(report.symbol()).isEqualTo("000977");
        assertThat(report.totalItems()).isEqualTo(2);
        assertThat(report.verifiedCount()).isEqualTo(2);
        assertThat(report.partialCount()).isEqualTo(0);
        assertThat(report.steps()).extracting(EvidenceReviewStep::stepCode)
                .containsExactly("COLLECT_GAPS", "SEARCH_ONLINE_SOURCES", "CLASSIFY_EVIDENCE", "AGENT_REVIEW_GATE");
        assertThat(report.items()).extracting(EvidenceReviewItem::reviewStatus)
                .containsOnly("VERIFIED");
        assertThat(report.conclusions()).anyMatch(item -> item.contains("已核实 2 项"));
    }

    private AgentConsensusReport consensus(CompanyProfile company) {
        AgentOpinion opinion = new AgentOpinion(
                "MOAT_INVESTIGATOR",
                "公告壁垒 Agent",
                "从公告中验证业务兑现",
                "REVIEW",
                "要求复核",
                new BigDecimal("62.00"),
                new BigDecimal("68.00"),
                List.of("公告正文事件 1 条"),
                List.of("缺少历史估值分位"),
                List.of("重大合同后续交付和回款", "3/5/10 年估值分位"),
                List.of(
                        new AgentEvidenceCheck(
                                "重大合同后续交付和回款",
                                "MISSING",
                                "未找到",
                                "在线证据源",
                                "初次搜索未命中",
                                null,
                                0
                        ),
                        new AgentEvidenceCheck(
                                "3/5/10 年估值分位",
                                "MISSING",
                                "未找到",
                                "实时行情/估值源",
                                "只找到 PE/PB",
                                null,
                                0
                        )
                ),
                null,
                null,
                null
        );
        return new AgentConsensusReport(
                company.symbol(),
                company.name(),
                "EVIDENCE_REVIEW",
                "证据复核",
                new BigDecimal("61.00"),
                "需要先补齐关键证据",
                0,
                0,
                1,
                0,
                List.of(opinion),
                List.of(),
                List.of("公告壁垒 Agent：缺少历史估值分位"),
                List.of("重大合同后续交付和回款", "3/5/10 年估值分位"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private CompanyResearchView research(CompanyProfile company) {
        FilingDocument document = new FilingDocument(
                "ann-1",
                "关于重大合同交付进展的公告",
                "巨潮资讯",
                "业务验证",
                "2026-06-21",
                "https://example.com/detail",
                "https://example.com/file.pdf",
                List.of("重大合同", "合同", "项目", "交付"),
                82
        );
        FilingEvent event = new FilingEvent(
                "VALIDATION",
                "兑现线索",
                "MEDIUM",
                "ann-1",
                document.title(),
                "公司重大合同进入项目交付阶段。",
                document.sourceUrl(),
                86
        );
        FilingEvidenceSummary filing = new FilingEvidenceSummary(
                company.symbol(),
                "LIVE",
                "巨潮实时公告",
                1,
                1,
                List.of(document),
                List.of(event),
                List.of(),
                List.of(),
                List.of(event.evidenceText()),
                List.of(),
                Instant.parse("2026-06-21T00:00:00Z")
        );
        return new CompanyResearchView(
                company,
                new BigDecimal("66.00"),
                "EVIDENCE_REVIEW",
                "证据复核",
                "继续补证",
                List.of(dimension("MOAT"), dimension("VALUATION")),
                List.of(new EvidenceTier("FILING", "公告/年报证据", 82, List.of(document.title()))),
                List.of(),
                List.of("补齐历史估值分位"),
                List.of("缺少 10 年历史估值分位"),
                List.of("巨潮资讯"),
                filing,
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private FinancialHistoryReport financialHistory(CompanyProfile company) {
        return new FinancialHistoryReport(
                company.symbol(),
                company.name(),
                "USABLE_SEQUENCE",
                "多年序列可用",
                5,
                new BigDecimal("72.00"),
                new BigDecimal("0.12"),
                new BigDecimal("0.32"),
                new BigDecimal("0.08"),
                new BigDecimal("0.11"),
                5,
                0,
                List.of(new FinancialMetricPoint(
                        company.symbol(),
                        company.name(),
                        "2025-12-31",
                        "2025年 年报",
                        new BigDecimal("0.12"),
                        new BigDecimal("1.23"),
                        new BigDecimal("0.32"),
                        new BigDecimal("0.08"),
                        new BigDecimal("0.11"),
                        new BigDecimal("1.20"),
                        new BigDecimal("8.80")
                )),
                List.of("已获取 5 个年度财务样本"),
                List.of("仍缺资本开支"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private ValuationHistoryReport valuationHistory(CompanyProfile company) {
        return new ValuationHistoryReport(
                company.symbol(),
                company.name(),
                "MID_PERCENTILE",
                "历史中位区间",
                6,
                new BigDecimal("37.74"),
                new BigDecimal("4.33"),
                new BigDecimal("0.50"),
                new BigDecimal("0.42"),
                new BigDecimal("32.00"),
                new BigDecimal("3.80"),
                new BigDecimal("18.00"),
                new BigDecimal("52.00"),
                new BigDecimal("2.20"),
                new BigDecimal("5.10"),
                peerValuation(company),
                List.of(),
                List.of("已按年报 EPS/BPS 与年末收盘价生成 6 个年度估值样本。"),
                List.of("当前为年末估值样本，不是日频或月频 PE/PB 序列。"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private PeerValuationReport peerValuation(CompanyProfile company) {
        return new PeerValuationReport(
                "INDUSTRY",
                "同行业可比",
                3,
                new BigDecimal("37.74"),
                new BigDecimal("4.33"),
                new BigDecimal("42.00"),
                new BigDecimal("4.80"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00"),
                new BigDecimal("0.33"),
                new BigDecimal("0.33"),
                1,
                1,
                List.of(new PeerValuationCompany(
                        "000938",
                        "紫光股份",
                        "IT服务",
                        company.themeCode(),
                        new BigDecimal("40.00"),
                        new BigDecimal("4.60"),
                        new BigDecimal("28.00"),
                        new BigDecimal("500000000"),
                        "同行业"
                )),
                List.of("已基于同行业可比形成 3 个可比样本。"),
                List.of()
        );
    }

    private DimensionScore dimension(String code) {
        return new DimensionScore(
                code,
                code,
                new BigDecimal("66.00"),
                "待验证",
                List.of(),
                List.of()
        );
    }

    private CompanyProfile company() {
        return new CompanyProfile(
                "000977",
                "浪潮信息",
                "深交所",
                "计算机设备",
                "DIGITAL_INFRA",
                new BigDecimal("72.00"),
                new BigDecimal("65.66"),
                BigDecimal.ONE,
                new BigDecimal("37.74"),
                new BigDecimal("4.33"),
                BigDecimal.ONE,
                new BigDecimal("4556060000"),
                "https://quote.example.com",
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("服务器核心技术"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO, "operating_cash_flow_per_share", new BigDecimal("1.23")),
                List.of()
        );
    }
}
