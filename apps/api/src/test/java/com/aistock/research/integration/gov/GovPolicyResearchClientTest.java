package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GovPolicyResearchClientTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    private RuntimeConfigStore config;
    private GovPolicyArchiveRepository archive;
    private MockRestServiceServer server;
    private RestClient restClient;
    private GovPolicyClient client;

    @BeforeEach void setUp() {
        config = mock(RuntimeConfigStore.class);
        archive = mock(GovPolicyArchiveRepository.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        client = new GovPolicyClient(restClient, new ObjectMapper(), config, CLOCK, archive);
        when(archive.read(any(), any(), any())).thenReturn(List.of());
    }

    @Test void researchIncludes730DayBoundaryAndReservesHistoryWhileLegacyRemains90Days() {
        source("json", "https://example.gov.cn/list.json");
        String json = "[" + row("new1", TODAY.toString()) + "," + row("new2", TODAY.minusDays(1).toString()) + ","
                + row("old", TODAY.minusDays(400).toString()) + "," + row("boundary", TODAY.minusDays(730).toString()) + ","
                + row("expired", TODAY.minusDays(731).toString()) + "," + row("future", TODAY.plusDays(1).toString()) + ","
                + row("future-time", "2026-10-17T13:00:00Z") + "]";
        expect("https://example.gov.cn/list.json", json, MediaType.APPLICATION_JSON);
        expect("https://example.gov.cn/list.json", json, MediaType.APPLICATION_JSON);
        var research = client.fetchResearchPoliciesWithStatus(4);
        assertThat(research.items()).extracting(GovPolicyItem::url).containsExactly(
                "https://example.gov.cn/new1", "https://example.gov.cn/new2", "https://example.gov.cn/old", "https://example.gov.cn/boundary");
        assertThat(research.items()).noneMatch(GovPolicyItem::planningBaseline);
        assertThat(research.failedSources()).contains(GovPolicyClient.HISTORY_COVERAGE_GAP);
        clearInvocations(archive);
        assertThat(client.fetchLatestPoliciesWithStatus(10).items()).extracting(GovPolicyItem::url)
                .doesNotContain("https://example.gov.cn/old", "https://example.gov.cn/boundary");
        verifyNoInteractions(archive);
        server.verify();
    }

    @Test void onlyFollowsObservedSameListPaginationIncludingDirectoryIndex() {
        source("html", "https://example.gov.cn/news/");
        expect("https://example.gov.cn/news/", """
                <li><a href='/news/new.htm'>新能源汽车产业项目集中投产</a><span>2026-10-17</span></li>
                <div class='pagination'><a href='index_1.htm'>下一页</a>
                <a href='https://evil.example/index_1.htm'>下一页</a><a href='/other/index_1.htm'>下一页</a>
                <a href='article.htm'>下一页</a><a href='index_9.htm'>产业政策导航栏目</a></div>
                """, MediaType.TEXT_HTML);
        expect("https://example.gov.cn/news/index_1.htm", """
                <li><a href='/news/old.htm'>固态电池产业项目集中投产</a><span>2025-01-17</span></li>
                <a href='index_1.htm' rel='next'>下一页</a>
                """, MediaType.TEXT_HTML);
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).extracting(GovPolicyItem::url).containsExactly("https://example.gov.cn/news/new.htm", "https://example.gov.cn/news/old.htm");
        assertThat(result.failedSources()).contains(GovPolicyClient.HISTORY_PAGE_LOOP_GAP);
        server.verify();
    }

    @Test void paginationStopsAtSixPagesAndReportsRemainingCoverage() {
        source("html", "https://example.gov.cn/news/index.html");
        for (int i = 0; i < 6; i++) {
            String url = "https://example.gov.cn/news/" + (i == 0 ? "index.html" : "index_" + i + ".html");
            expect(url, "<li><a href='article" + i + ".html'>新能源汽车产业项目集中投产</a><span>2025-01-17</span></li>"
                    + "<a href='index_" + (i + 1) + ".html' rel='next'>下一页</a>", MediaType.TEXT_HTML);
        }
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).hasSize(6);
        assertThat(result.failedSources()).contains(GovPolicyClient.HISTORY_PAGE_LIMIT_GAP);
        server.verify();
    }

    @Test void partialPageFailureKeepsSuccessfulPageAndArchivedHistory() {
        PolicySourceConfig source = source("html", "https://example.gov.cn/news/index.html");
        when(archive.read(eq(GovPolicyArchiveRepository.sourceKey(source)), any(), any())).thenReturn(List.of(
                item("https://example.gov.cn/saved", "2025-03-01")));
        expect(source.url(), "<li><a href='new.htm'>新能源汽车产业项目集中投产</a><span>2026-10-17</span></li><a href='index_1.html'>下一页</a>", MediaType.TEXT_HTML);
        server.expect(requestTo("https://example.gov.cn/news/index_1.html")).andRespond(withServerError());
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).hasSize(2);
        assertThat(result.failedSources()).contains(GovPolicyClient.HISTORY_PAGE_FAILURE_GAP);
        verify(archive).upsert(eq(GovPolicyArchiveRepository.sourceKey(source)), anyList(), eq(TODAY.minusDays(730)), eq(TODAY));
        server.verify();
    }

    @Test void archiveFailuresExposeOnlyFixedMessagesAndKeepCurrentData() {
        source("json", "https://example.gov.cn/list.json");
        when(archive.read(any(), any(), any())).thenThrow(new IllegalStateException("sensitive-connection-string"));
        doThrow(new IllegalStateException("secret-password")).when(archive).upsert(any(), any(), any(), any());
        expect("https://example.gov.cn/list.json", "[" + row("current", TODAY.toString()) + "]", MediaType.APPLICATION_JSON);
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).hasSize(1);
        assertThat(result.failedSources()).contains(GovPolicyClient.HISTORY_ARCHIVE_READ_GAP, GovPolicyClient.HISTORY_ARCHIVE_WRITE_GAP)
                .noneMatch(gap -> gap.contains("sensitive") || gap.contains("secret"));
    }

    @Test void changedOrRemovedSourceNeverReadsOldSourceArchive() {
        var original = new PolicySourceConfig("相同来源", "json", "https://example.gov.cn/old.json", 80);
        var changed = new PolicySourceConfig("相同来源", "json", "https://example.gov.cn/new.json", 80);
        when(config.readPolicySources()).thenReturn(List.of(changed)).thenReturn(List.of());
        expect(changed.url(), "[]", MediaType.APPLICATION_JSON);
        client.fetchResearchPoliciesWithStatus(20);
        client.fetchResearchPoliciesWithStatus(20);
        verify(archive, never()).read(eq(GovPolicyArchiveRepository.sourceKey(original)), any(), any());
        verify(archive, never()).readExcluded(GovPolicyArchiveRepository.sourceKey(original));
        verify(archive, times(1)).read(eq(GovPolicyArchiveRepository.sourceKey(changed)), any(), any());
        verify(archive, times(1)).readExcluded(GovPolicyArchiveRepository.sourceKey(changed));
        assertThat(GovPolicyArchiveRepository.sourceKey(original)).isNotEqualTo(GovPolicyArchiveRepository.sourceKey(changed));
    }

    @Test void verifiedExpiredArticleIsExcludedFromDisplayWithoutForgettingItsDate() {
        PolicySourceConfig source = source("html", "https://example.gov.cn/list/index.html");
        String article = "https://example.gov.cn/old-report.html";
        expect(source.url(), "<li><a href='" + article + "'>未来制造产业发展报告意见</a><span>2026-10-17</span></li>", MediaType.TEXT_HTML);
        expect(article, "<meta name='PubDate' content='2020-01-01'><h1>未来制造产业发展报告意见</h1><article><p>加快固态电池关键技术研发，推动产业项目有序开展和示范应用。</p></article>", MediaType.TEXT_HTML);
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).isEmpty();
        verify(archive).exclude(eq(GovPolicyArchiveRepository.sourceKey(source)), argThat(rows -> rows.size() == 1
                && rows.get(0).url().equals(article) && rows.get(0).publishedAt().equals("2020-01-01")));
        verify(archive).upsert(eq(GovPolicyArchiveRepository.sourceKey(source)), eq(List.of()), any(), any());
        server.verify();
    }

    @Test void verifiedExpiredDateSurvivesRestartAndCannotBeReplacedByAListTitle() {
        PolicySourceConfig source = source("html", "https://example.gov.cn/list/index.html");
        String article = "https://example.gov.cn/old-report.html";
        var exclusions = new java.util.ArrayList<GovPolicyItem>();
        when(archive.readExcluded(eq(GovPolicyArchiveRepository.sourceKey(source)))).thenAnswer(call -> List.copyOf(exclusions));
        doAnswer(call -> {
            exclusions.addAll(call.<List<GovPolicyItem>>getArgument(1));
            return null;
        }).when(archive).exclude(eq(GovPolicyArchiveRepository.sourceKey(source)), anyList());
        String listing = "<li><a href='" + article + "'>未来制造产业发展报告意见</a><span>2026-10-17</span></li>";
        expect(source.url(), listing, MediaType.TEXT_HTML);
        expect(article, "<meta name='PubDate' content='2020-01-01'><article><p>加快固态电池关键技术研发，推动产业项目有序开展和示范应用。</p></article>", MediaType.TEXT_HTML);
        expect(source.url(), listing, MediaType.TEXT_HTML);
        server.expect(ExpectedCount.between(0, 1), requestTo(article)).andRespond(withServerError());

        assertThat(client.fetchResearchPoliciesWithStatus(20).items()).isEmpty();
        assertThat(exclusions).singleElement().extracting(GovPolicyItem::publishedAt).isEqualTo("2020-01-01");
        var restarted = new GovPolicyClient(restClient, new ObjectMapper(), config, CLOCK, archive);
        assertThat(restarted.fetchResearchPoliciesWithStatus(20).items()).isEmpty();
        verify(archive, times(2)).upsert(eq(GovPolicyArchiveRepository.sourceKey(source)), eq(List.of()), any(), any());
        server.verify();
    }

    @Test void aPreviouslyFutureVerifiedArticleCanBecomeEligibleWithoutChangingItsDate() {
        PolicySourceConfig source = source("html", "https://example.gov.cn/list/index.html");
        GovPolicyItem known = new GovPolicyItem("测试来源", "html", "固态电池产业报告",
                "https://example.gov.cn/known-report.html", TODAY.toString(), 95,
                "推动固态电池关键技术研发，后续产业化进展待核验。", false);
        when(archive.readExcluded(GovPolicyArchiveRepository.sourceKey(source))).thenReturn(List.of(known));
        expect(source.url(), "<li><a href='" + known.url() + "'>固态电池产业报告</a><span>2026-10-17</span></li>", MediaType.TEXT_HTML);

        assertThat(client.fetchResearchPoliciesWithStatus(20).items()).containsExactly(known);
        verify(archive).upsert(eq(GovPolicyArchiveRepository.sourceKey(source)), eq(List.of(known)), any(), any());
        server.verify();
    }

    @Test void archivedPlanSurvivesCutoffButFuturePlanDoesNot() {
        var source = source("plan", "https://example.gov.cn/plan.html");
        when(archive.read(any(), any(), any())).thenReturn(List.of(
                new GovPolicyItem("源", "plan", "旧产业发展规划文件", "https://example.gov.cn/old-plan.html", "2020-01-01", 90, "固态电池规划正文", true),
                new GovPolicyItem("源", "plan", "未来产业发展规划文件", "https://example.gov.cn/future-plan.html", "2027-01-01", 90, "规划正文", true)));
        server.expect(requestTo(source.url())).andRespond(withServerError());
        assertThat(client.fetchResearchPoliciesWithStatus(20).items()).extracting(GovPolicyItem::url)
                .containsExactly("https://example.gov.cn/old-plan.html");
    }

    @Test void historyQuotaDoesNotAllowOneBusySourceToCrowdOutAnother() {
        var first = new PolicySourceConfig("来源甲", "json", "https://example.gov.cn/a.json", 90);
        var second = new PolicySourceConfig("来源乙", "json", "https://example.gov.cn/b.json", 90);
        when(config.readPolicySources()).thenReturn(List.of(first, second));
        expect(first.url(), "[" + row("a1", "2026-10-17") + "," + row("a2", "2026-10-16") + "," + row("a3", "2025-10-17") + "," + row("a4", "2025-10-16") + "]", MediaType.APPLICATION_JSON);
        expect(second.url(), "[" + row("b1", "2026-09-17") + "," + row("b2", "2025-09-17") + "]", MediaType.APPLICATION_JSON);
        assertThat(client.fetchResearchPoliciesWithStatus(4).items()).extracting(GovPolicyItem::url)
                .containsExactly("https://example.gov.cn/a1", "https://example.gov.cn/b1", "https://example.gov.cn/a3", "https://example.gov.cn/b2");
    }

    private PolicySourceConfig source(String type, String url) {
        var result = new PolicySourceConfig("测试来源", type, url, 95);
        when(config.readPolicySources()).thenReturn(List.of(result));
        return result;
    }

    private void expect(String url, String body, MediaType contentType) {
        server.expect(requestTo(url)).andRespond(withSuccess(body, contentType));
    }

    private String row(String id, String date) {
        return "{\"title\":\"新能源汽车产业项目集中投产\",\"url\":\"https://example.gov.cn/" + id + "\",\"publishedAt\":\"" + date + "\"}";
    }

    private GovPolicyItem item(String url, String date) {
        return new GovPolicyItem("测试来源", "html", "新能源汽车产业项目集中投产", url, date, 95);
    }
}
