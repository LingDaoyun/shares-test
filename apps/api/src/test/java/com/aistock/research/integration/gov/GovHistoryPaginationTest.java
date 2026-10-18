package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GovHistoryPaginationTest {
    private static final String NDRC_FORMULA = """
            function createPageHTML(_nPageCount,_nCurrIndex,_sPageName,_sPageExt){
                var nCurrIndex = _nCurrIndex; var nextPageNum=nCurrIndex+1;
                document.write("<li class=\\"cur\\"><a href=\\""+_sPageName+"_"+nextPageNum+"."+_sPageExt+"\\" >下一页</a></li>");
            }
            """;

    @Test void ndrcRequiresObservedCallAndPublicFormulaBeforeGeneratingNextPage() {
        String url = "https://www.ndrc.gov.cn/fggz/";
        var result = GovHistoryPagination.discover(url, url, ndrc(0), scriptUrl -> {
            assertThat(scriptUrl).isEqualTo("https://www.ndrc.gov.cn/jslm/fenye/fenye.js");
            return NDRC_FORMULA;
        });
        assertThat(result.nextPages()).containsExactly("https://www.ndrc.gov.cn/fggz/index_1.html");
        assertThat(result.unrecognized()).isFalse();
        assertThat(GovHistoryPagination.discover(url, url, ndrc(0), ignored -> "function unrelated() {}"))
                .isEqualTo(GovHistoryPagination.Result.unsupported());
        assertThat(GovHistoryPagination.discover(url, url, ndrc(0).replace("../jslm", "https://evil.example/jslm"),
                ignored -> { throw new AssertionError("Untrusted script must not be fetched"); }))
                .isEqualTo(GovHistoryPagination.Result.unsupported());
    }

    @Test void mofUsesActiveInlineFormulaRatherThanCommentedCreatePageCall() {
        String url = "https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/";
        String html = """
                <script>
                //createPageHTML(20,0,"index","htm")
                var currentPage = 0;//所在页从0开始
                var nextPage = currentPage+1//下一页
                var countPage = 20//共多少页
                if(countPage>1&&currentPage!=(countPage-1)) document.write("<span><a href=\\"index"+"_" + nextPage + "."+"htm\\">></a></span>");
                </script>
                """;
        assertThat(GovHistoryPagination.discover(url, url, html, ignored -> { throw new AssertionError(); }).nextPages())
                .containsExactly(url + "index_1.htm");
        assertThat(GovHistoryPagination.discover(url, url, html.replace("nextPage +", "somethingElse +"), ignored -> "").unrecognized()).isTrue();
    }

    @Test void moaParsesStaticPageContextAndPreservesCategoryWithoutFollowingSearchBranch() {
        String configured = "https://www.moa.gov.cn/govpublic/1/";
        String html = moa(0);
        assertThat(GovHistoryPagination.discover(configured, configured, html, ignored -> "").nextPages())
                .containsExactly(configured + "index_1.htm?id=");
        assertThat(GovHistoryPagination.discover(configured, configured + "index_1.htm?id=123", moa(1), ignored -> "").nextPages())
                .containsExactly(configured + "index_2.htm?id=123");
        assertThat(GovHistoryPagination.discover(configured, configured + "index_19.htm?id=", moa(19), ignored -> "").nextPages()).isEmpty();
    }

    @Test void ignoresCommentedCodeAndRejectsMismatchedPageOrIncompleteEvidence() {
        String url = "https://www.ndrc.gov.cn/fggz/";
        assertThat(GovHistoryPagination.discover(url, url,
                "<!-- <script>createPageHTML(20,0,'index','html')</script> -->", ignored -> "").nextPages()).isEmpty();
        assertThat(GovHistoryPagination.discover(url, url,
                "<script>// createPageHTML(20,0,'index','html')\n/* createPageHTML(20,0,'index','html') */</script>", ignored -> "").nextPages()).isEmpty();
        assertThat(GovHistoryPagination.discover(url, url + "index_4.html", ndrc(0), ignored -> NDRC_FORMULA).unrecognized()).isTrue();
        assertThat(GovHistoryPagination.discover("https://www.most.gov.cn/xxgk/", "https://www.most.gov.cn/xxgk/",
                "<script>var pagination_script_config={pageNumber:'0',total:'46'};</script>", ignored -> "").unrecognized()).isTrue();
        assertThat(GovHistoryPagination.discover("https://evil.example/", "https://evil.example/", ndrc(0), ignored -> NDRC_FORMULA).nextPages()).isEmpty();
    }

    @Test void collectorFollowsSixActualNdrcPagesWithCachedFormulaAndExplicitRemainingGap() {
        RuntimeConfigStore config = mock(RuntimeConfigStore.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new GovPolicyClient(builder.build(), new ObjectMapper(), config,
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        String base = "https://www.ndrc.gov.cn/fggz/";
        when(config.readPolicySources()).thenReturn(List.of(new PolicySourceConfig("国家发展改革委", "html", base, 95)));
        for (int index = 0; index < 6; index++) {
            String url = index == 0 ? base : base + "index_" + index + ".html";
            String article = "<li><a href='industry" + index + ".html'>新能源汽车产业项目集中投产</a><span>2025-10-17</span></li>";
            server.expect(requestTo(url)).andRespond(withSuccess(article + ndrc(index), MediaType.TEXT_HTML));
            if (index == 0) server.expect(requestTo("https://www.ndrc.gov.cn/jslm/fenye/fenye.js"))
                    .andRespond(withSuccess(NDRC_FORMULA, MediaType.valueOf("application/javascript")));
        }
        var result = client.fetchResearchPoliciesWithStatus(20);
        assertThat(result.items()).hasSize(6);
        assertThat(result.failedSources()).contains(GovPolicyClient.HISTORY_PAGE_LIMIT_GAP)
                .doesNotContain(GovPolicyClient.HISTORY_PAGINATION_UNSUPPORTED_GAP);
        server.verify();
    }

    private String ndrc(int current) {
        return "<script src='../jslm/fenye/fenye.js'></script><script type='text/javascript'>\n<!--\ncreatePageHTML(20, "
                + current + ", \"index\", \"html\");\n//-->\n</script>";
    }

    private String moa(int current) {
        return """
                <script>
                var cid=""; var classId=getUrlParam("id"); if(classId!=null) cid=classId;
                var m_sPageName="index"; var m_sPageExt="htm";
                PageContext.PageNav.go=function(_iPage,_maxPage){
                  if(_iPage>_maxPage){_iPage=_maxPage;}
                  if(_iPage>20){window.location.href="http://www.moa.gov.cn/govsearch/simp_gov_list.jsp?page="+_iPage+"&ORGANCAT="+cid;return;}
                  PageContext.params={"CurrPage":_iPage};var sURL="";
                  if(_iPage==1){sURL=m_sPageName+"."+m_sPageExt;}else{sURL=m_sPageName+"_"+(_iPage-1)+"."+m_sPageExt;}
                  window.location.href=sURL+"?id="+cid;
                };
                var m_nRecordCount="17341";var m_nCurrPage=CURRENT;var m_nPageSize=20;
                </script>
                """.replace("CURRENT", Integer.toString(current));
    }
}
