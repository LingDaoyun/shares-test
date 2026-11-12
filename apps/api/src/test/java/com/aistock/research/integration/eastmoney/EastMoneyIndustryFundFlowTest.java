package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyIndustryFundFlowTest {
    @Test
    void readsOfficialIndustryFeedWithRealTimestampAndCorrectPercentageUnits() {
        FeedClient client = new FeedClient("""
                {"rc":0,"data":{"total":2,"diff":[
                  {"f12":"BK1036","f14":"半导体","f62":2352556544,
                   "f184":80,"f66":658246144,"f69":22,"f72":1694310400,"f75":57,
                   "f104":47,"f105":140,"f124":1794469197,"f152":2},
                  {"f12":"BK0475","f14":"银行","f62":-1230000,
                   "f184":-125,"f66":0,"f69":0,"f72":-1230000,"f75":-125,
                   "f104":2,"f105":40,"f124":1794469197,"f152":2}
                ]}}
                """);

        var rows = client.fetchIndustryFundFlows();

        assertThat(client.requestedUrl).startsWith("https://data.eastmoney.com/dataapi/bkzj/getbkzj?")
                .contains("code=m%3A90%2Bs%3A4").contains("f124").contains("f152");
        assertThat(rows).hasSize(2);
        var semiconductor = rows.get(0);
        assertThat(semiconductor.mainNetInflow()).isEqualByComparingTo("2352556544");
        assertThat(semiconductor.superLargeNetInflow()).isEqualByComparingTo("658246144");
        assertThat(semiconductor.largeNetInflow()).isEqualByComparingTo("1694310400");
        assertThat(semiconductor.mainNetInflowRatio()).isEqualByComparingTo("0.80");
        assertThat(semiconductor.superLargeNetInflowRatio()).isEqualByComparingTo("0.22");
        assertThat(semiconductor.largeNetInflowRatio()).isEqualByComparingTo("0.57");
        assertThat(semiconductor.constituentCount()).isEqualTo(187);
        assertThat(semiconductor.marketTimestamp()).isEqualTo(Instant.parse("2026-11-12T07:39:57Z"));
        assertThat(semiconductor.tradeDate()).isEqualTo(LocalDate.parse("2026-11-12"));
        assertThat(semiconductor.sourceUrl()).isEqualTo(client.requestedUrl);
        assertThat(rows.get(1).mainNetInflowRatio()).isEqualByComparingTo("-1.25");
        assertThat(rows.get(1).superLargeNetInflowRatio()).isZero();
    }

    @Test
    void preservesMissingMetricsAndDateInsteadOfFillingThemWithZeroOrFetchDate() {
        var rows = new FeedClient("""
                {"rc":0,"data":{"total":1,"diff":[
                  {"f12":"BK1036","f14":"半导体","f62":"-","f184":"-","f152":2}
                ]}}
                """).fetchIndustryFundFlows();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.mainNetInflow()).isNull();
            assertThat(row.mainNetInflowRatio()).isNull();
            assertThat(row.tradeDate()).isNull();
            assertThat(row.marketTimestamp()).isNull();
        });
    }

    @Test
    void rejectsTruncatedOrDuplicateIndustryCoverage() {
        String row = "{\"f12\":\"BK1036\",\"f14\":\"半导体\",\"f62\":100,\"f152\":2}";
        for (String diff : new String[]{row, row + "," + row}) {
            FeedClient client = new FeedClient("{\"rc\":0,\"data\":{\"total\":2,\"diff\":[" + diff + "]}}");
            assertThatThrownBy(client::fetchIndustryFundFlows)
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("行业资金流返回范围不完整");
        }
    }

    private static class FeedClient extends EastMoneyClient {
        private final String response;
        private String requestedUrl;

        FeedClient(String response) {
            super(null, new ObjectMapper(), null);
            this.response = response;
        }

        @Override
        String fetchBodyWithCurl(String url, Exception cause, String referer) {
            requestedUrl = url;
            return response;
        }
    }
}
