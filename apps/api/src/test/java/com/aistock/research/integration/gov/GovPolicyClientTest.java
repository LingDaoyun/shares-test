package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.fiveday.FiveDayContextModels;
import com.aistock.research.fiveday.FiveDayEventEvidenceService;
import com.aistock.research.integration.cninfo.CninfoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class GovPolicyClientTest {

    private RuntimeConfigStore store;
    private MockRestServiceServer server;
    private GovPolicyClient client;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GovPolicyClient(
                builder.build(),
                new ObjectMapper(),
                store
        );
    }

    @Test
    void nextFetchUsesTheLatestDatabasePolicySources() {
        when(store.readPolicySources())
                .thenReturn(List.of(new PolicySourceConfig(
                        "来源甲", "html", "https://policy-a.example/list", 90)))
                .thenReturn(List.of(new PolicySourceConfig(
                        "来源乙", "html", "https://policy-b.example/list", 80)));
        server.expect(requestTo("https://policy-a.example/list"))
                .andRespond(withSuccess(
                        "<a href='/a'>政策方案甲正式发布通知</a>",
                        new MediaType("text", "html", UTF_8)));
        server.expect(requestTo("https://policy-b.example/list"))
                .andRespond(withSuccess(
                        "<a href='/b'>政策方案乙正式发布通知</a>",
                        new MediaType("text", "html", UTF_8)));

        GovPolicyFetchResult first = client.fetchLatestPoliciesWithStatus(8);
        GovPolicyFetchResult second = client.fetchLatestPoliciesWithStatus(8);

        assertThat(first.items()).extracting(GovPolicyItem::source).containsOnly("来源甲");
        assertThat(second.items()).extracting(GovPolicyItem::source).containsOnly("来源乙");
        server.verify();
    }

    @Test
    void explicitlyEmptyDatabaseListDisablesPolicyFetching() {
        when(store.readPolicySources()).thenReturn(List.of());

        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(8);

        assertThat(result.items()).isEmpty();
        assertThat(result.failedSources()).isEmpty();
        server.verify();
    }

    @Test
    void jsonPublicationValuesKeepTheirOriginalTimePrecision() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("JSON政策源", "json", "https://policy.example/list.json", 80)));
        server.expect(requestTo("https://policy.example/list.json")).andRespond(withSuccess("""
                [
                  {"title":"半导体未来政策", "url":"https://policy.example/future", "publishedAt":" 2026-09-08T03:00:00Z "},
                  {"title":"半导体已发布政策", "url":"https://policy.example/recent", "publishedAt":"2026-09-08 09:00:00"},
                  {"title":"半导体按日公开政策", "url":"https://policy.example/date", "publishedAt":"2026-09-07"}
                ]
                """, MediaType.APPLICATION_JSON));

        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(40);

        assertThat(result.items()).extracting(GovPolicyItem::publishedAt)
                .containsExactly("2026-09-08T03:00:00Z", "2026-09-08 09:00:00", "2026-09-07");
        server.verify();
    }

    @Test
    void futureIntradayPolicyCannotPassThroughRealClientParsing() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("JSON政策源", "json", "https://policy.example/list.json", 80)));
        server.expect(requestTo("https://policy.example/list.json")).andRespond(withSuccess("""
                [{"title":"半导体未来政策", "url":"https://policy.example/future", "publishedAt":"2026-09-08T03:00:00Z"}]
                """, MediaType.APPLICATION_JSON));
        Instant asOf = Instant.parse("2026-09-08T02:00:00Z");
        FiveDayEventEvidenceService service = new FiveDayEventEvidenceService(client,
                mock(CninfoClient.class), Clock.fixed(asOf.plusSeconds(2), ZoneOffset.UTC));

        FiveDayContextModels.PolicyBundle result = service.fetchPolicies(asOf);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.notes()).anyMatch(note -> note.contains("未来日期"));
        server.verify();
    }

    @Test
    void reportsAnUnusableSourceInsteadOfTreatingEmptyHtmlAsCompleteCoverage() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("产业新闻源", "html", "https://policy.example/list", 90)));
        server.expect(requestTo("https://policy.example/list")).andRespond(withSuccess(
                "<a href='/search'>产业政策检索服务</a>", new MediaType("text", "html", UTF_8)));
        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(40);
        assertThat(result.items()).isEmpty();
        assertThat(result.failedSources()).singleElement().asString().contains("产业新闻源", "未解析到");
        server.verify();
    }

    @Test
    void collectsIndustrialNewsAlongsidePolicyDocumentsAndRejectsNavigation() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("产业新闻源", "html", "https://policy.example/list", 90)));
        server.expect(requestTo("https://policy.example/list")).andRespond(withSuccess("""
                <li><a href='/news'>新能源汽车产业项目集中投产</a><span>2026-10-17</span></li>
                <li><a href='/fund'>超长期特别国债支持消费品以旧换新资金已全部下达</a><span>2026-09-30</span></li>
                <li><a href='/plan'>人工智能产业发展行动方案</a><span>2026-09-29</span></li>
                <li><a href='/navigation'>新能源产业政策检索服务</a></li>
                <li><a href='/other'>机关开展内部职工文体活动</a></li>
                """, new MediaType("text", "html", UTF_8)));

        assertThat(client.fetchLatestPoliciesWithStatus(40).items())
                .extracting(GovPolicyItem::title)
                .containsExactly("新能源汽车产业项目集中投产", "超长期特别国债支持消费品以旧换新资金已全部下达", "人工智能产业发展行动方案");
        server.verify();
    }

    @Test
    void htmlPublicationDateRetainsItsAvailableDayPrecision() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("HTML政策源", "html", "https://policy.example/list", 80)));
        server.expect(requestTo("https://policy.example/list")).andRespond(withSuccess("""
                <li><a href='/recent'>半导体产业发展实施意见</a><span>2026年9月7日</span></li>
                """, new MediaType("text", "html", UTF_8)));

        assertThat(client.fetchLatestPoliciesWithStatus(40).items()).singleElement()
                .satisfies(row -> assertThat(row.publishedAt()).isEqualTo("2026-09-07"));
        server.verify();
    }

    @Test
    void planningSourceExtractsSolidStateBatteryParagraphWithoutNavigationNoise() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("部委规划", "plan", "https://policy.example/plan", 95)));
        server.expect(requestTo("https://policy.example/plan")).andRespond(withSuccess("""
                <html><head><meta name="ArticleTitle" content="未来产业发展规划">
                <meta name="PubDate" content="2026-09-28 09:30:00"></head><body>
                <nav>导航噪声 固态电池热搜</nav><h1>未来产业发展规划</h1>
                <div class="TRS_Editor"><p>第一章：产业技术方向。</p>
                <p>加快固态电池和固态电解质等关键技术研发。</p><p>到2030年1月1日形成示范应用。</p>
                <script>不可信脚本导航</script><div class="related-links">关联推荐噪声</div></div></body></html>
                """, new MediaType("text", "html", UTF_8)));

        GovPolicyItem result = client.fetchLatestPoliciesWithStatus(40).items().get(0);

        assertThat(result.title()).isEqualTo("未来产业发展规划");
        assertThat(result.bodyText()).contains("第一章：产业技术方向。\n", "固态电池", "固态电解质")
                .doesNotContain("导航噪声", "不可信脚本", "关联推荐");
        assertThat(result.publishedAt()).isEqualTo("2026-09-28 09:30:00");
        assertThat(result.planningBaseline()).isTrue();
        server.verify();
    }

    @Test
    void unknownDocumentPublicationDateIsNotInferredFromTargetsOrNavigation() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("规划解读", "document", "https://policy.example/explain", 90)));
        server.expect(requestTo("https://policy.example/explain")).andRespond(withSuccess("""
                <header>2026年9月28日 星期一</header><h1>未来产业发展规划解读</h1>
                <div id="zoom"><p>到2030年1月1日，推动固态电池等关键技术形成示范。</p></div>
                """, new MediaType("text", "html", UTF_8)));
        GovPolicyItem result = client.fetchLatestPoliciesWithStatus(40).items().get(0);
        assertThat(result.bodyText()).contains("固态电池");
        assertThat(result.publishedAt()).isNull();
        assertThat(result.planningBaseline()).isFalse();
        server.verify();
    }

    @Test
    void refusesNavigationOnlyPageInsteadOfInventingDocumentBody() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("失效规划页", "plan", "https://policy.example/broken", 90)));
        server.expect(requestTo("https://policy.example/broken")).andRespond(withSuccess("""
                <h1>某产业规划</h1><nav><a href='/p'>固态电池发展规划</a></nav><footer>版权所有</footer>
                """, new MediaType("text", "html", UTF_8)));
        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(40);
        assertThat(result.items()).isEmpty();
        assertThat(result.failedSources()).anyMatch(gap -> gap.contains("正文"));
        server.verify();
    }

    @Test
    void keepsListTitleWhenPlanningBodyRequestFailsAndDoesNotExposeResponseBody() {
        when(store.readPolicySources()).thenReturn(List.of(new PolicySourceConfig("列表源", "json", "https://policy.example/list.json", 80)));
        server.expect(requestTo("https://policy.example/list.json")).andRespond(withSuccess(
                "[{\"title\":\"未来产业发展规划\",\"url\":\"https://policy.example/article\",\"date\":\"2026-09-28\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://policy.example/article")).andRespond(withServerError().body("raw secret-looking response"));

        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(40);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("未来产业发展规划");
            assertThat(item.bodyText()).isNull();
            assertThat(item.planningBaseline()).isFalse();
        });
        assertThat(result.failedSources()).singleElement().asString().contains("正文").doesNotContain("raw secret");
        server.verify();
    }

    @Test
    void configuredPlanningBaselineSurvivesLatestLimitAndIsDownloadedOnlyOnce() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("最新源", "json", "https://policy.example/latest.json", 90),
                new PolicySourceConfig("规划基线", "plan", "https://policy.example/baseline", 95)));
        server.expect(requestTo("https://policy.example/latest.json")).andRespond(withSuccess(
                "[{\"title\":\"最新消费支持通知\",\"url\":\"https://policy.example/latest\",\"date\":\"2026-10-17\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://policy.example/baseline")).andRespond(withSuccess(
                "<h1>长期产业规划</h1><meta name='PubDate' content='2025-01-01'><div class='TRS_Editor'><p>重点研究固态电池关键技术，加强相关产业技术验证。</p></div>",
                new MediaType("text", "html", UTF_8)));
        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.planningBaseline()).isTrue();
            assertThat(item.publishedAt()).isEqualTo("2025-01-01");
        });
        server.verify();
    }

    @Test
    void enrichesAtMostFourSelectedPlanningTitlesAndRetainsOthers() {
        when(store.readPolicySources()).thenReturn(List.of(new PolicySourceConfig("列表", "json", "https://policy.example/list.json", 80)));
        String rows = java.util.stream.IntStream.range(0, 6).mapToObj(i ->
                "{\"title\":\"产业发展规划" + i + "\",\"url\":\"https://policy.example/plan" + i + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        server.expect(requestTo("https://policy.example/list.json")).andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 4; i++) server.expect(requestTo("https://policy.example/plan" + i)).andRespond(withSuccess(
                "<div class='TRS_Editor'><p>稳步推进固态电池关键技术研发以及示范验证工作。</p></div>", new MediaType("text", "html", UTF_8)));
        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(40);
        assertThat(result.items()).hasSize(6);
        assertThat(result.items().stream().filter(item -> item.bodyText() != null)).hasSize(4);
        assertThat(result.items()).allMatch(item -> !item.planningBaseline());
        server.verify();
    }

    @Test
    void keepsSixArgumentConstructionAndBatteryIndustryNewsCompatible() {
        GovPolicyItem old = new GovPolicyItem("源", "html", "标题", "https://policy.example/old", null, 90);
        assertThat(old.bodyText()).isNull();
        assertThat(old.planningBaseline()).isFalse();
        when(store.readPolicySources()).thenReturn(List.of(new PolicySourceConfig("电池新闻", "html", "https://policy.example/news", 90)));
        server.expect(requestTo("https://policy.example/news")).andRespond(withSuccess(
                "<li><a href='/battery'>固态电池关键技术取得突破</a><span>2026-09-28</span></li>", new MediaType("text", "html", UTF_8)));
        assertThat(client.fetchLatestPoliciesWithStatus(40).items()).extracting(GovPolicyItem::title)
                .containsExactly("固态电池关键技术取得突破");
        server.verify();
    }

    @Test
    void decodesOfficialRepostWithoutHttpCharsetAndKeepsOnlyItsArticleContainer() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("湖北省经信厅", "plan", "https://policy.example/repost", 90)));
        String body = """
                <html><head><meta charset="UTF-8"><meta name="ArticleTitle" content="关于推进电子信息产业发展的规划">
                <meta name="PubDate" content="2026-09-29 17:44"><meta name="ContentSource" content="工信微报"></head>
                <body><header>政务导航噪声</header><div class="article-box"><div class="view TRS_UEDITOR">
                <p>推动全固态电池和关键材料研发，加快产业创新应用。</p><p>本文有删减。</p>
                <p>工业和信息化部 2026年9月14日</p></div></div><footer>其他政策导航噪声</footer></body></html>
                """;
        server.expect(requestTo("https://policy.example/repost"))
                .andRespond(withSuccess(body.getBytes(UTF_8), MediaType.TEXT_HTML));

        GovPolicyItem item = client.fetchLatestPoliciesWithStatus(40).items().get(0);

        assertThat(item.title()).isEqualTo("关于推进电子信息产业发展的规划");
        assertThat(item.bodyText()).contains("全固态电池", "本文有删减", "2026年9月14日")
                .doesNotContain("导航噪声");
        assertThat(item.bodyText().length()).isBetween(50, 120);
        assertThat(item.publishedAt()).isEqualTo("2026-09-29 17:44");
        server.verify();
    }

    @Test
    void listTitleTargetDateAndUnrelatedNavigationDateAreNotPublicationEvidence() {
        when(store.readPolicySources()).thenReturn(List.of(
                new PolicySourceConfig("列表源", "html", "https://policy.example/list", 90)));
        server.expect(requestTo("https://policy.example/list")).andRespond(withSuccess("""
                <header>当前日期2026年10月17日</header>
                <li><a href='/target'>到2030年1月1日实现电池产业升级的实施方案</a></li>
                <a href='/notice'>重点产业技术研发支持通知</a><span>2026年10月17日</span>
                """, new MediaType("text", "html", UTF_8)));
        assertThat(client.fetchLatestPoliciesWithStatus(40).items())
                .hasSize(2).allMatch(item -> item.publishedAt() == null);
        server.verify();
    }
}
