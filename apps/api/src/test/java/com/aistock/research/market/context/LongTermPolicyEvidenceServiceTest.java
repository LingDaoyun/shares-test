package com.aistock.research.market.context;

import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTermPolicyEvidenceServiceTest {

    private final GovPolicyClient govPolicyClient = mock(GovPolicyClient.class);
    private final LongTermPolicyEvidenceService service = new LongTermPolicyEvidenceService(govPolicyClient);

    @Test
    void keepsOnlyRecentRelevantOfficialDocumentsAndCapsAtFive() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy("国家发展改革委", "关于促进电力市场建设的意见", "https://www.ndrc.gov.cn/xxgk/zcfb/202607/a.html", "2026-07-10", 95),
                policy("国家能源局", "新型电力系统建设行动方案", "https://www.nea.gov.cn/202606/b.html", "2026-06-08", 92),
                policy("中国政府网", "能源绿色低碳转型实施方案", "https://www.gov.cn/zhengce/202605/c.html", "2026-05-01", 100),
                policy("生态环境部", "电力行业碳排放管理办法", "https://www.mee.gov.cn/zcwj/202604/d.html", "2026-04-03", 90),
                policy("工业和信息化部", "电力装备高质量发展指导意见", "https://www.miit.gov.cn/zwgk/202603/e.html", "2026-03-02", 90),
                policy("国务院国资委", "中央企业能源保供工作通知", "https://www.sasac.gov.cn/n2588025/202602/f.html", "2026-02-01", 88),
                policy("商业媒体", "电力板块投资机会", "https://finance.example.com/a.html", "2026-07-20", 99),
                policy("中国政府网", "关于教育改革的意见", "https://www.gov.cn/zhengce/202607/g.html", "2026-07-15", 100),
                policy("国家能源局", "电力行业历史文件", "https://www.nea.gov.cn/202301/h.html", "2023-01-01", 92)
        )));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).hasSize(5);
        assertThat(evidence.documents()).allSatisfy(document -> {
            assertThat(document.url()).contains(".gov.cn");
            assertThat(document.matchedKeywords()).isNotEmpty();
            assertThat(document.relevanceScore()).isGreaterThanOrEqualTo(58);
        });
        assertThat(evidence.documents()).extracting(LongTermPolicyDocument::title)
                .doesNotContain("电力板块投资机会", "关于教育改革的意见", "电力行业历史文件");
    }

    @Test
    void classifiesConstraintPoliciesSeparatelyFromSupport() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy("国家发展改革委", "关于规范煤炭产能和限制新增项目的通知",
                        "https://www.ndrc.gov.cn/xxgk/zcfb/202607/a.html", "2026-07-10", 95)
        )));

        LongTermPolicyEvidence evidence = service.evaluate("煤炭开采", "煤炭样本");

        assertThat(evidence.documents()).singleElement()
                .extracting(LongTermPolicyDocument::impact)
                .isEqualTo("CONSTRAINT");
    }

    @Test
    void returnsAnExplicitGapInsteadOfUnrelatedPolicies() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy("中国政府网", "关于教育改革的意见",
                        "https://www.gov.cn/zhengce/202607/g.html", "2026-07-15", 100)
        )));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).isEmpty();
        assertThat(evidence.dataGaps()).contains("最近两年未匹配到可靠官方政策文件");
    }

    @Test
    void reportsTheUpstreamFailureWithoutInventingDocuments() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60))
                .thenThrow(new IllegalStateException("全部失败"));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).isEmpty();
        assertThat(evidence.dataGaps()).contains("政府政策源暂不可用：全部失败");
    }

    @Test
    void recordsPartialSourceFailuresWhileKeepingReliableDocuments() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(new GovPolicyFetchResult(
                List.of(policy(
                        "国家能源局",
                        "新型电力系统建设行动方案",
                        "https://www.nea.gov.cn/202606/b.html",
                        "2026-06-08",
                        92
                )),
                List.of("工业和信息化部：连接超时")
        ));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).hasSize(1);
        assertThat(evidence.dataGaps()).contains("部分政策源不可用：工业和信息化部：连接超时");
    }

    @Test
    void rejectsFutureDatedPolicies() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy(
                        "国家能源局",
                        "新型电力系统建设行动方案",
                        "https://www.nea.gov.cn/202701/b.html",
                        "2027-01-08",
                        92
                )
        )));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).isEmpty();
        assertThat(evidence.dataGaps()).contains("最近两年未匹配到可靠官方政策文件");
    }

    @Test
    void keepsPartialSourceFailuresWhenNoDocumentMatches() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(new GovPolicyFetchResult(
                List.of(policy(
                        "中国政府网",
                        "关于教育改革的意见",
                        "https://www.gov.cn/zhengce/202607/g.html",
                        "2026-07-15",
                        100
                )),
                List.of("国家能源局：连接超时")
        ));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).isEmpty();
        assertThat(evidence.dataGaps()).containsExactly(
                "最近两年未匹配到可靠官方政策文件",
                "部分政策源不可用：国家能源局：连接超时"
        );
    }

    @Test
    void onlyCountsDirectlyRelevantOfficialRecordsAsAgreement() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy(
                        "国家能源局",
                        "新型能源电力系统建设行动方案",
                        "https://www.nea.gov.cn/202606/b.html",
                        "2026-06-08",
                        92
                ),
                policy(
                        "商业媒体",
                        "新型电力系统建设提速",
                        "https://finance.example.com/202606/c.html",
                        "2026-06-09",
                        99
                ),
                policy(
                        "国家发展改革委",
                        "关于促进能源高质量发展的意见",
                        "https://www.ndrc.gov.cn/xxgk/zcfb/202607/a.html",
                        "2026-07-10",
                        95
                )
        )));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).singleElement()
                .satisfies(document -> assertThat(document.rationale()).doesNotContain("另有"));
    }

    @Test
    void rejectsPoliciesThatOnlyMatchABroadRelatedWord() {
        when(govPolicyClient.fetchLatestPoliciesWithStatus(60)).thenReturn(result(List.of(
                policy(
                        "国家发展改革委",
                        "关于促进能源高质量发展的意见",
                        "https://www.ndrc.gov.cn/xxgk/zcfb/202607/a.html",
                        "2026-07-10",
                        95
                )
        )));

        LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");

        assertThat(evidence.documents()).isEmpty();
        assertThat(evidence.dataGaps()).contains("最近两年未匹配到可靠官方政策文件");
    }

    private GovPolicyFetchResult result(List<GovPolicyItem> items) {
        return new GovPolicyFetchResult(items, List.of());
    }

    private GovPolicyItem policy(
            String source,
            String title,
            String url,
            String publishedAt,
            int sourceWeight
    ) {
        return new GovPolicyItem(source, "html", title, url, publishedAt, sourceWeight);
    }
}
