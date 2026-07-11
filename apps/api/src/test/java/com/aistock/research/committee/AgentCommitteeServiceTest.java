package com.aistock.research.committee;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.evidence.AgentEvidenceSearchService;
import com.aistock.research.filing.FilingDocument;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.filing.FilingEvidenceSummary;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;
import com.aistock.research.research.DimensionScore;
import com.aistock.research.research.EvidenceTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCommitteeServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final CompanyResearchService companyResearchService = mock(CompanyResearchService.class);
    private final AgentCommitteeService service = new AgentCommitteeService(
            companyService,
            companyResearchService,
            new AgentEvidenceSearchService()
    );

    @Test
    void shouldBuildFiveAgentConsensus() {
        CompanyProfile company = company(false);
        when(companyService.getCompany("000977")).thenReturn(company);
        when(companyResearchService.analyze(company)).thenReturn(research(company, List.of()));

        AgentConsensusReport report = service.discuss("000977");

        assertThat(report.opinions()).hasSize(5);
        assertThat(report.opinions()).allSatisfy(opinion -> assertThat(opinion.evidenceChecks()).isNotEmpty());
        assertThat(report.consensusScore()).isGreaterThan(new BigDecimal("50"));
        assertThat(report.requiredEvidence()).isNotEmpty();
    }

    @Test
    void shouldRouteHardBlockToRiskReview() {
        CompanyProfile company = company(true);
        when(companyService.getCompany("000977")).thenReturn(company);
        when(companyResearchService.analyze(company)).thenReturn(research(company, List.of("ST 风险因子触发")));

        AgentConsensusReport report = service.discuss("000977");

        assertThat(report.consensusStage()).isEqualTo("RISK_REVIEW");
        assertThat(report.vetoCount()).isGreaterThan(0);
    }

    private CompanyResearchView research(CompanyProfile company, List<String> hardBlocks) {
        FilingDocument document = new FilingDocument(
                "ann-1",
                "关于核心技术研发和重大合同进展的公告",
                "巨潮资讯",
                "业务验证",
                "2026-06-21",
                "https://example.com/detail",
                "https://example.com/file.pdf",
                List.of("核心技术", "重大合同", "研发", "合同"),
                82
        );
        FilingEvent event = new FilingEvent(
                "MOAT",
                "壁垒线索",
                "MEDIUM",
                "ann-1",
                document.title(),
                "公司核心技术持续自主研发，重大合同进入交付。",
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
                List.of(event.evidenceText()),
                List.of(),
                List.of("重大合同进入交付"),
                List.of(),
                Instant.parse("2026-06-21T00:00:00Z")
        );
        return new CompanyResearchView(
                company,
                hardBlocks.isEmpty() ? new BigDecimal("72.00") : new BigDecimal("50.00"),
                hardBlocks.isEmpty() ? "WAIT_FOR_PRICE" : "RISK_REVIEW",
                hardBlocks.isEmpty() ? "等待价格" : "风险复核",
                hardBlocks.isEmpty() ? "继续验证" : "硬风险触发",
                List.of(
                        dimension("TREND", "趋势匹配", "72"),
                        dimension("QUALITY", "财务质量", hardBlocks.isEmpty() ? "66" : "40"),
                        dimension("MOAT", "核心壁垒", "78"),
                        dimension("VALUATION", "估值安全边际", "64"),
                        dimension("RISK", "风险排雷", hardBlocks.isEmpty() ? "78" : "38")
                ),
                List.of(
                        new EvidenceTier("POLICY", "政策/主题线索", 70, List.of("主题映射 / 数字基础设施")),
                        new EvidenceTier("MARKET", "行情与估值证据", 62, List.of("实时行情 / 测试行情")),
                        new EvidenceTier("FILING", "公告/年报证据", 82, List.of("巨潮资讯 / " + document.title())),
                        new EvidenceTier("VALIDATION", "订单/招投标/财务兑现", 72, List.of("巨潮资讯 / " + document.title()))
                ),
                hardBlocks,
                List.of("下载并阅读最新公告 PDF"),
                List.of("缺少 10 年历史估值分位"),
                List.of("巨潮资讯"),
                filing,
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private DimensionScore dimension(String code, String name, String score) {
        return new DimensionScore(
                code,
                name,
                new BigDecimal(score),
                name + "待验证",
                List.of(name + "证据"),
                List.of(name + "核查")
        );
    }

    private CompanyProfile company(boolean st) {
        return new CompanyProfile(
                "000977",
                st ? "*ST样本" : "浪潮信息",
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
                st ? null : "2025-12-31",
                st ? null : "2025年 年报",
                true,
                List.of("服务器核心技术", "高端客户认证"),
                st ? List.of("ST 风险") : List.of("需复核公告风险"),
                Map.of("st_flag", st ? BigDecimal.ONE : BigDecimal.ZERO),
                List.of()
        );
    }
}
