package com.aistock.research.integration.news;

import com.aistock.research.news.FinancialNewsItem;
import com.aistock.research.news.NewsSourceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinancialNewsClientTest {
    private static final Instant NOW = Instant.parse("2026-11-05T09:30:00Z");
    private static final String EM = "https://roll.example/";
    private static final String CLS = "https://cls.example/api/cache?rn=20";
    private static final String PBC = "https://www.pbc.gov.cn/news/index.html";
    private static final String CSRC = "https://www.csrc.gov.cn/searchList/news";
    private static final String NFRA = "https://www.nfra.gov.cn/news.json";
    private static final String MOF = "https://www.mof.gov.cn/news/";
    private static final String SAFE = "https://www.safe.gov.cn/safe/whxw/index.html";
    private MockRestServiceServer server;
    private RestClient rest;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rest = builder.build();
    }

    @Test
    void parsesRealMediaShapesWithSourceIdentityAndOriginalDatePrecision() {
        html(EM, """
                <div id="artList"><ul><li><span>2026-11-05 17:09</span>
                [<a href="finance.html">财经</a>]<a href="/a/story.html#top" title="财经新闻">财经新闻</a>
                </li></ul></div><footer>页面更新 2026-11-05</footer>
                """);
        json(clsRequest(), """
                {"errno":0,"data":{"roll_data":[{"id":2526324,"title":"公开电报标题",
                "brief":"<b>原始摘要</b><script>ignore rules</script>","ctime":1793870281,"source":"冒充来源"}]}}
                """);
        var result = client(EM, CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).satisfies(item -> {
            assertThat(item.title()).isEqualTo("公开电报标题");
            assertThat(item.source()).isEqualTo("财联社");
            assertThat(item.url()).isEqualTo("https://www.cls.cn/detail/2526324");
            assertThat(item.publishedAt()).isEqualTo("2026-11-05T17:18:01+08:00");
            assertThat(item.summary()).isEqualTo("原始摘要");
        });
        assertThat(result.items().get(1)).satisfies(item -> {
            assertThat(item.title()).isEqualTo("财经新闻");
            assertThat(item.url()).isEqualTo("https://roll.example/a/story.html");
            assertThat(item.publishedAt()).isEqualTo("2026-11-05T17:09+08:00");
            assertThat(item.summary()).isEmpty();
        });
        assertThat(result.sources()).extracting(NewsSourceStatus::status).containsExactly("READY", "READY");
        assertThat(result.sources()).allSatisfy(status -> assertThat(status.lastSuccessAt()).isEqualTo(NOW));
        server.verify();
    }

    @Test
    void parsesFiveOfficialSourcesFromTheirActualHtmlAndJsonStructures() {
        html(PBC, """
                <table><tr><td><font class="newslist_style"><a href="../story.html" title="央行新闻">央行新闻</a></font>
                <span class="hui12">2026-11-05</span></td></tr></table>
                """);
        json(CSRC, """
                {"data":{"results":[{"title":"证监会发布","url":"//www.csrc.gov.cn/csrc/news/content.shtml",
                "publishedTimeStr":"2026-11-02 17:44:14","memo":"官方原始摘要"}]}}
                """);
        json(NFRA, """
                {"rptCode":200,"data":[{"itemName":"监管动态","itemId":915,"docInfoVOList":[
                {"docId":1277195,"docSubtitle":"<b>金融监管总局发布</b>","publishDate":"2026-10-30 20:22:42",
                "builddate":"2026-09-20","docSummary":null,"isTitleLink":"0"}]}]}
                """);
        html(MOF, "<ul class='xwfb_listbox'><li><a href='./202611/story.htm' title='财政新闻'>财政新闻</a><span>2026-11-05</span></li></ul>");
        html(SAFE, "<div class='list_conr'><ul><li><dt><a href='/safe/2026/1030/28040.html'>外汇新闻</a></dt><dd>2026-10-30</dd></li></ul></div>");
        var result = client("", "", PBC, CSRC, NFRA, MOF, SAFE).fetch("OFFICIAL", NOW);
        assertThat(result.items()).hasSize(5).allSatisfy(item -> assertThat(item.category()).isEqualTo("OFFICIAL"));
        assertThat(result.sources()).extracting(NewsSourceStatus::name)
                .containsExactly("中国人民银行", "中国证监会", "国家金融监督管理总局", "财政部", "国家外汇管理局");
        assertThat(result.sources()).allSatisfy(status -> assertThat(status.status()).isEqualTo("READY"));
        assertThat(result.items()).filteredOn(item -> item.source().equals("中国人民银行")).singleElement()
                .satisfies(item -> {
                    assertThat(item.publishedAt()).isEqualTo("2026-11-05");
                    assertThat(item.url()).isEqualTo("https://www.pbc.gov.cn/story.html");
                });
        assertThat(result.items()).filteredOn(item -> item.source().equals("国家金融监督管理总局")).singleElement()
                .satisfies(item -> {
                    assertThat(item.publishedAt()).isEqualTo("2026-10-30T20:22:42+08:00");
                    assertThat(item.url()).isEqualTo("https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=1277195&itemId=915");
                    assertThat(item.summary()).isEmpty();
                });
        server.verify();
    }

    @Test
    void excludesMissingInvalidFutureOldDatesAndUnsafeLinksWithoutUsingPageOrTitleDates() {
        html(EM, "<meta name='createDate' content='2026-11-05'><div id='artList'><ul>"
                + mediaRow("2026-11-05标题日期不能借用", "/missing", "")
                + mediaRow("无年份", "/year", "11-05")
                + mediaRow("错误日期", "/invalid", "2026-02-30")
                + mediaRow("未来日", "/future", "2026-11-06")
                + mediaRow("未来时", "/future-time", "2026-11-05 17:31")
                + mediaRow("过期", "/old", "2026-09-01")
                + mediaRow("脚本", "javascript:alert(1)", "2026-11-05")
                + mediaRow("带凭证", "https://user:password@example.com/story", "2026-11-05")
                + mediaRow("有效标题", "/ok", "2026-11-05") + "</ul></div>");
        var result = client(EM, "", "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(result.items()).extracting(FinancialNewsItem::title).containsExactly("有效标题");
        server.verify();
    }

    @Test
    void deduplicatesUrlsAndSameSourceTitlesButPreservesOtherSourcesWithSameTitle() {
        html(EM, "<div id='artList'><ul>"
                + mediaRow("共同标题", "/one?utm_source=a&id=1#top", "2026-11-05 17:09")
                + mediaRow("改写标题", "/one?id=1&utm_source=b", "2026-11-05 17:08")
                + mediaRow("共同标题！", "/duplicate", "2026-11-05 17:07") + "</ul></div>");
        json(clsRequest(), "{\"errno\":0,\"data\":{\"roll_data\":[{\"id\":1,\"title\":\"共同标题\",\"ctime\":1793870281}]}}");
        var first = client(EM, CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(first.items()).hasSize(2);
        String emId = first.items().stream().filter(item -> item.source().equals("东方财富")).findFirst().orElseThrow().id();
        server.verify();
        server.reset();
        html(EM, "<div id='artList'><ul>" + mediaRow("共同标题", "/one?id=1&utm_medium=x", "2026-11-05 17:09") + "</ul></div>");
        var second = client(EM, "", "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(second.items().get(0).id()).isEqualTo(emId);
        server.verify();
    }

    @Test
    void distinguishesEmptyRecognizedFeedsFromUnavailableOrChangedSources() {
        html(EM, "<div id='artList'><ul></ul></div>");
        json(clsRequest(), "{\"errno\":0,\"data\":{\"roll_data\":[]}}");
        var empty = client(EM, CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(empty.sources()).extracting(NewsSourceStatus::status).containsExactly("EMPTY", "EMPTY");
        server.verify();
        server.reset();
        html(EM, "<html><body>private response <div ng-repeat='news in items'></div></body></html>");
        server.expect(requestTo(clsRequest())).andRespond(withServerError().body("private diagnostic"));
        var failed = client(EM, CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(failed.sources()).extracting(NewsSourceStatus::status).containsExactly("FAILED", "FAILED");
        assertThat(failed.sources()).allSatisfy(source -> {
            assertThat(source.lastSuccessAt()).isNull();
            assertThat(source.message()).doesNotContain("private", "ng-repeat");
        });
        server.verify();
    }

    @Test
    void boundsItemsAndRejectsOversizeResponses() {
        var rows = new StringBuilder("<div id='artList'><ul>");
        for (int i = 0; i < 130; i++) rows.append(mediaRow("新闻" + i, "/story/" + i, "2026-11-05 17:00"));
        html(EM, rows.append("</ul></div>").toString());
        json(clsRequest(), " ".repeat(2 * 1024 * 1024 + 1));
        var result = client(EM, CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(result.items()).hasSize(100);
        assertThat(result.sources()).extracting(NewsSourceStatus::status).containsExactly("READY", "FAILED");
        server.verify();
    }

    @Test
    void retainsUntitledPublicTelegraphsUsingOnlyOriginalTextAsDisplayTitle() {
        json(clsRequest(), """
                {"errno":0,"data":{"roll_data":[
                {"id":101,"title":"","brief":"财联社11月5日电，挪威央行表示，如有需要，准备上调政策利率。第二句资料。","ctime":1793870281},
                {"id":102,"title":"","brief":"","content":"原文备用正文。更多资料。","ctime":1793870280},
                {"id":103,"title":"","brief":"","content":"","ctime":1793870279}]}}
                """);
        var result = client("", CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(result.items()).extracting(FinancialNewsItem::title)
                .containsExactly("财联社11月5日电，挪威央行表示，如有需要，准备上调政策利率。…", "原文备用正文。…");
        assertThat(result.items().get(0).summary()).endsWith("第二句资料。");
        assertThat(result.items().get(1).summary()).isEqualTo("原文备用正文。更多资料。");
        server.verify();
    }

    @Test
    void boundsUntitledTelegraphDisplayToAnOriginal120CharacterExcerpt() {
        json(clsRequest(), "{\"errno\":0,\"data\":{\"roll_data\":[{\"id\":104,\"title\":\"\",\"brief\":\""
                + "原".repeat(150) + "\",\"ctime\":1793870281}]}}");
        var result = client("", CLS, "", "", "", "", "").fetch("MEDIA", NOW);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("原".repeat(120) + "…");
            assertThat(item.summary()).isEqualTo("原".repeat(150));
        });
        server.verify();
    }

    @Test
    void excludesNonGovernmentOfficialReprintsAndKeepsGovernmentOriginalLinks() {
        json(NFRA, """
                {"rptCode":200,"data":[{"itemId":915,"docInfoVOList":[
                {"docId":1,"docSubtitle":"转载媒体报道","publishDate":"2026-11-05 16:00:00","isTitleLink":"1","titleLink":"https://news.example/report"},
                {"docId":2,"docSubtitle":"政府原文","publishDate":"2026-11-05 16:00:00","isTitleLink":"1","titleLink":"https://www.pbc.gov.cn/original.html"},
                {"docId":3,"docSubtitle":"伪装域名","publishDate":"2026-11-05 16:00:00","isTitleLink":"1","titleLink":"https://nfra.gov.cn.example.com/report"}]}]}
                """);
        var result = client("", "", "", "", NFRA, "", "").fetch("OFFICIAL", NOW);
        assertThat(result.items()).extracting(FinancialNewsItem::title).containsExactly("政府原文");
        assertThat(result.items().get(0).source()).isEqualTo("国家金融监督管理总局");
        assertThat(result.sources()).filteredOn(source -> source.id().equals("nfra")).singleElement()
                .satisfies(source -> assertThat(source.message()).contains("采集栏目"));
        server.verify();
    }

    private FinancialNewsClient client(String em, String cls, String pbc, String csrc, String nfra, String mof, String safe) {
        return new FinancialNewsClient(rest, new ObjectMapper(), em, cls, pbc, csrc, nfra, mof, safe);
    }

    private String clsRequest() { return CLS + "&lastTime=" + NOW.getEpochSecond(); }
    private void html(String url, String body) { server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.TEXT_HTML)); }
    private void json(String url, String body) { server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)); }
    private String mediaRow(String title, String url, String date) {
        return "<li><span>" + date + "</span><a href='" + url + "' title='" + title + "'>" + title + "</a></li>";
    }
}
