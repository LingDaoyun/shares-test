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
}
