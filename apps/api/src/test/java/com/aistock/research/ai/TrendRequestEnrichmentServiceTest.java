package com.aistock.research.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendRequestEnrichmentServiceTest {

    private final TrendRequestEnrichmentService service = new TrendRequestEnrichmentService(RestClient.create());

    @Test
    void extractsGovArticleBodyFromKnownContentContainer() {
        String html = """
                <html>
                <head><meta name="description" content="meta description" /></head>
                <body>
                  <div id="UCAP-CONTENT">
                    <div class="trs_editor_view">
                      <p>国务院关于印发《现代化应急体系建设“十五五”规划》的通知</p>
                      <p>现将《现代化应急体系建设“十五五”规划》印发给你们，请认真贯彻执行。</p>
                      <p>积极探索人工智能、云计算等新技术应用，强化会商研判、应急指挥辅助决策支撑。</p>
                    </div>
                  </div>
                </body>
                </html>
                """;

        String extracted = service.extractMainContent(html);

        assertThat(extracted).contains("现代化应急体系建设“十五五”规划");
        assertThat(extracted).contains("积极探索人工智能、云计算等新技术应用");
        assertThat(extracted).doesNotContain("meta description");
    }

    @Test
    void keepsLongerFetchedBodyWhenOriginalExcerptIsTooShort() {
        TrendPromptRequest request = new TrendPromptRequest(
                "测试规划",
                "政府规划文件",
                "测试机构",
                "2026-06-14",
                "https://example.com/policy",
                "简短摘要",
                List.of("数字基础设施"),
                List.of("示例公司")
        );

        assertThat(service.shouldFetch(request)).isTrue();
    }
}
