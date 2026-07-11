package com.aistock.research.selection;

import com.aistock.research.committee.AgentCommitteeService;
import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockSelectionServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final AgentCommitteeService agentCommitteeService = mock(AgentCommitteeService.class);
    private final StockSelectionService service = new StockSelectionService(companyService, agentCommitteeService);

    @Test
    void shouldSelectAcrossWholeMarketAndKeepTraceableDiscussion() {
        CompanyProfile sh = company("600519", "贵州茅台", "上交所");
        CompanyProfile sz = company("000977", "浪潮信息", "深交所");
        CompanyProfile cy = company("300394", "天孚通信", "深交所");

        when(companyService.listCompanies()).thenReturn(List.of(sh, sz, cy));
        when(agentCommitteeService.discuss("600519")).thenReturn(report(sh, "WAIT_FOR_PRICE", "等待价格", "68", 1, 3, 1, 0));
        when(agentCommitteeService.discuss("000977")).thenReturn(report(sz, "EVIDENCE_REVIEW", "证据复核", "60", 0, 3, 1, 1));
        when(agentCommitteeService.discuss("300394")).thenReturn(report(cy, "VALUATION_WATCH", "估值观察", "74", 3, 2, 0, 0));

        StockSelectionReport report = service.shortlist(2, 3);

        assertThat(report.scope()).isEqualTo("全市场公司池");
        assertThat(report.universeCount()).isEqualTo(3);
        assertThat(report.reviewedCount()).isEqualTo(3);
        assertThat(report.candidates()).extracting(StockSelectionCandidate::symbol)
                .containsExactly("300394", "600519");
        assertThat(report.candidates().get(0).rank()).isEqualTo(1);
        assertThat(report.candidates().get(0).discussion().opinions()).isNotNull();
        assertThat(report.candidates().get(0).trace())
                .extracting(StockSelectionTraceStep::stepCode)
                .contains("UNIVERSE_SCREEN", "AGENT_DISCUSSION", "FINAL_SHORTLIST");
    }

    private AgentConsensusReport report(
            CompanyProfile company,
            String stage,
            String label,
            String score,
            int support,
            int watch,
            int review,
            int veto
    ) {
        return new AgentConsensusReport(
                company.symbol(),
                company.name(),
                stage,
                label,
                new BigDecimal(score),
                "测试共识理由",
                support,
                watch,
                review,
                veto,
                List.of(),
                List.of("多数 Agent 认可继续跟踪"),
                veto > 0 ? List.of("财务质量 Agent：需要补证") : List.of(),
                List.of("主营收入拆分", "公告正文核验"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private CompanyProfile company(String symbol, String name, String market) {
        return new CompanyProfile(
                symbol,
                name,
                market,
                "测试行业",
                "DIGITAL_INFRA",
                new BigDecimal("72.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("28.00"),
                new BigDecimal("3.20"),
                BigDecimal.ONE,
                new BigDecimal("100000000"),
                "https://quote.example.com/" + symbol,
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("核心资产线索"),
                List.of(),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of()
        );
    }
}
