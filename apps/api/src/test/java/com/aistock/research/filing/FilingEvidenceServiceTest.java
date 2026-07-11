package com.aistock.research.filing;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.integration.cninfo.CninfoAnnouncement;
import com.aistock.research.integration.cninfo.CninfoClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilingEvidenceServiceTest {

    private final CninfoClient cninfoClient = mock(CninfoClient.class);
    private final FilingPdfTextService filingPdfTextService = mock(FilingPdfTextService.class);
    private final FilingEventExtractor filingEventExtractor = new FilingEventExtractor();
    private final FilingEvidenceService service = new FilingEvidenceService(
            cninfoClient,
            filingPdfTextService,
            filingEventExtractor,
            properties()
    );

    @Test
    void shouldSummarizeLiveCninfoAnnouncements() {
        CninfoAnnouncement contract = new CninfoAnnouncement(
                "ann-1",
                "000977",
                "浪潮信息",
                "gssz0000977",
                "关于签订重大合同暨核心技术研发进展的公告",
                1781884800000L,
                "finalpage/2026-06-20/ann-1.PDF"
        );
        CninfoAnnouncement inquiry = new CninfoAnnouncement(
                "ann-2",
                "000977",
                "浪潮信息",
                "gssz0000977",
                "关于收到监管问询函的公告",
                1781798400000L,
                "finalpage/2026-06-19/ann-2.PDF"
        );
        when(cninfoClient.fetchAnnouncements(any(), anyInt())).thenReturn(List.of(contract, inquiry));
        when(cninfoClient.downloadUrl(contract.adjunctUrl())).thenReturn("https://static.cninfo.com.cn/ann-1.pdf");
        when(cninfoClient.downloadUrl(inquiry.adjunctUrl())).thenReturn("https://static.cninfo.com.cn/ann-2.pdf");
        when(cninfoClient.disclosureUrl(any(), any(), any(), anyLong())).thenReturn("https://www.cninfo.com.cn/detail");
        when(filingPdfTextService.extract(any(FilingDocument.class))).thenAnswer(invocation -> {
            FilingDocument document = invocation.getArgument(0);
            return Optional.of(new FilingTextSnapshot(
                    document.documentId(),
                    document.title(),
                    2,
                    "公司核心技术持续自主研发，研发投入稳定。公司签订重大合同并完成项目交付。公司收到监管问询函。"
            ));
        });

        FilingEvidenceSummary summary = service.summarize(company());

        assertThat(summary.status()).isEqualTo("LIVE");
        assertThat(summary.documents()).hasSize(2);
        assertThat(summary.parsedDocuments()).isGreaterThan(0);
        assertThat(summary.extractedEvents()).extracting(FilingEvent::eventType)
                .contains("RISK", "MOAT", "VALIDATION");
        assertThat(summary.moatSignals()).anyMatch(signal -> signal.contains("重大合同"));
        assertThat(summary.riskSignals()).anyMatch(signal -> signal.contains("问询函"));
        assertThat(summary.validationSignals()).anyMatch(signal -> signal.contains("重大合同"));
    }

    @Test
    void shouldFallbackToCompanyEvidenceWhenCninfoFails() {
        when(cninfoClient.fetchAnnouncements(any(), anyInt())).thenThrow(new IllegalStateException("blocked"));

        FilingEvidenceSummary summary = service.summarize(company());

        assertThat(summary.status()).isEqualTo("FALLBACK");
        assertThat(summary.documents()).isNotEmpty();
        assertThat(summary.dataGaps()).anyMatch(gap -> gap.contains("巨潮"));
    }

    private CompanyProfile company() {
        return new CompanyProfile(
                "000977",
                "浪潮信息",
                "深交所",
                "计算机设备",
                "DIGITAL_INFRA",
                new BigDecimal("86.00"),
                new BigDecimal("50.00"),
                BigDecimal.ONE,
                new BigDecimal("35.00"),
                new BigDecimal("4.00"),
                BigDecimal.ONE,
                new BigDecimal("200000000"),
                "https://quote.example.com",
                "测试源",
                "2026-06-21T09:00:00Z",
                "2025-12-31",
                "2025年 年报",
                true,
                List.of("服务器核心技术", "高端客户认证"),
                List.of("需复核公告风险"),
                Map.of("st_flag", BigDecimal.ZERO),
                List.of(
                        new EvidenceItem("年报指标", "2025年 年报", "研发投入稳定，核心技术持续迭代。", "https://report.example.com", 78),
                        new EvidenceItem("公告", "重大合同公告", "公司签订长期供货合同。", "https://notice.example.com", 76)
                )
        );
    }

    private LiveDataProperties properties() {
        return new LiveDataProperties(
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_URL,
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_MINUTE_URL,
                20,
                "https://quote.example.com",
                "https://financial.example.com",
                "https://cninfo.example.com",
                "https://policy.example.com",
                true,
                12,
                2,
                6,
                List.of()
        );
    }
}
