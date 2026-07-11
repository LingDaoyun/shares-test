package com.aistock.research.research;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.filing.FilingDocument;
import com.aistock.research.filing.FilingEvent;
import com.aistock.research.filing.FilingEvidenceSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyResearchServiceTest {

    private final CompanyResearchService service = new CompanyResearchService(company -> filingEvidence(company.symbol()));

    @Test
    void shouldBuildFiveDimensionResearchView() {
        CompanyResearchView view = service.analyze(company("688001", "样本科技", false));

        assertThat(view.dimensions()).extracting(DimensionScore::code)
                .containsExactly("TREND", "QUALITY", "MOAT", "VALUATION", "RISK");
        assertThat(view.evidenceTiers()).extracting(EvidenceTier::code)
                .containsExactly("POLICY", "MARKET", "FILING", "VALIDATION");
        assertThat(view.overallScore()).isGreaterThan(new BigDecimal("60"));
        assertThat(view.nextActions()).anyMatch(action -> action.contains("公告"));
        assertThat(view.dataGaps()).anyMatch(gap -> gap.contains("历史估值"));
        assertThat(view.filingEvidence().status()).isEqualTo("LIVE");
        assertThat(view.filingEvidence().moatSignals()).isNotEmpty();
    }

    @Test
    void shouldRouteStCompanyToRiskReview() {
        CompanyResearchView view = service.analyze(company("600001", "*ST样本", true));

        assertThat(view.stage()).isEqualTo("RISK_REVIEW");
        assertThat(view.hardBlocks()).isNotEmpty();
        assertThat(view.overallScore()).isLessThanOrEqualTo(new BigDecimal("59.00"));
    }

    private FilingEvidenceSummary filingEvidence(String symbol) {
        FilingDocument document = new FilingDocument(
                "ann-1",
                "关于签订重大合同暨核心产品研发进展的公告",
                "巨潮资讯",
                "业务验证",
                "2026-06-20",
                "https://example.com/detail",
                "https://example.com/file.pdf",
                List.of("重大合同", "核心技术", "研发", "合同"),
                82
        );
        return new FilingEvidenceSummary(
                symbol,
                "LIVE",
                "巨潮实时公告",
                1,
                1,
                List.of(document),
                List.of(new FilingEvent(
                        "MOAT",
                        "壁垒线索",
                        "MEDIUM",
                        "ann-1",
                        document.title(),
                        "核心技术持续自主研发，重大合同进入交付。",
                        "https://example.com/detail",
                        82
                )),
                List.of(document.title()),
                List.of(),
                List.of(document.title()),
                List.of("缺少最近年度报告或定期报告原文"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private CompanyProfile company(String symbol, String name, boolean st) {
        return new CompanyProfile(
                symbol,
                name,
                "上交所",
                "半导体",
                "DIGITAL_INFRA",
                new BigDecimal("82.00"),
                new BigDecimal("32.20"),
                new BigDecimal("1.20"),
                new BigDecimal("32.00"),
                new BigDecimal("3.20"),
                new BigDecimal("1.10"),
                new BigDecimal("120000000"),
                "https://quote.example.com/" + symbol,
                "测试源",
                "2026-06-21T09:00:00Z",
                st ? null : "2025-12-31",
                st ? null : "2025年 年报",
                true,
                List.of("核心产品自研", "高端客户认证", "研发投入稳定"),
                st ? List.of("名称包含 ST") : List.of("需要公告验证"),
                Map.of(
                        "roe_annual", new BigDecimal("0.13"),
                        "operating_cash_flow_per_share", new BigDecimal("1.20"),
                        "gross_margin", new BigDecimal("0.38"),
                        "revenue_growth", new BigDecimal("0.16"),
                        "pe_ttm", new BigDecimal("32.00"),
                        "pb", new BigDecimal("3.20"),
                        "turnover_rate", new BigDecimal("1.10"),
                        "amount", new BigDecimal("120000000"),
                        "st_flag", st ? BigDecimal.ONE : BigDecimal.ZERO,
                        "policy_theme_relevance", new BigDecimal("0.82")
                ),
                List.of(
                        new EvidenceItem("实时行情", "测试行情", "估值和成交额正常。", "https://quote.example.com", 82),
                        new EvidenceItem("年报指标", "2025年 年报", "ROE 与现金流为正。", "https://report.example.com", 78),
                        new EvidenceItem("主题映射", "数字基础设施", "命中数字、半导体主题。", "https://policy.example.com", 70)
                )
        );
    }
}
