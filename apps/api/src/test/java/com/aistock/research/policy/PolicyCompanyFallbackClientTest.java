package com.aistock.research.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PolicyCompanyFallbackClientTest {
    private MockRestServiceServer server;
    private PolicyCompanyFallbackClient client;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PolicyCompanyFallbackClient(builder.build(), new ObjectMapper());
    }

    @Test
    void verifiesRealIndustryMembershipAndSymbolsWithoutInventingQuoteOrFinancialData() {
        directory("{\"new_jxhy\":\"new_jxhy,机械行业,90\",\"new_jrhy\":\"new_jrhy,金融行业,30\"}");
        server.expect(requestTo(PolicyCompanyFallbackClient.nodeUrl("new_jxhy"))).andRespond(withSuccess("""
                [{"code":"600001","symbol":"sh600001","name":"设备样本","trade":99,"per":30,"ticktime":"2099-01-01"},
                 {"code":"600002","symbol":"sz600002","name":"交易所不符"},
                 {"code":"123456","symbol":"sh123456","name":"非法代码"},
                 {"code":"600003","symbol":"sh600003","name":"ST样本"}]
                """, MediaType.APPLICATION_JSON));
        var result = client.fetch(List.of(List.of("机械")));
        assertThat(result.companies()).singleElement().satisfies(company -> {
            assertThat(company.symbol()).isEqualTo("600001");
            assertThat(company.industry()).isEqualTo("机械行业");
            assertThat(company.quoteUrl()).isEqualTo(PolicyCompanyFallbackClient.nodeUrl("new_jxhy"));
            assertThat(company.latestPrice()).isNull();
            assertThat(company.peTtm()).isNull();
            assertThat(company.amount()).isNull();
            assertThat(company.financialReportDate()).isNull();
            assertThat(company.liveData()).isFalse();
        });
        server.verify();
    }

    @Test
    void capsTheWholeCollectionAtOneDirectoryAndElevenIndustryRequests() {
        StringBuilder directory = new StringBuilder("{");
        for (int i = 0; i < 15; i++) {
            if (i > 0) directory.append(',');
            directory.append('"').append("new_m").append(i).append("\":\"new_m").append(i).append(",机械分类").append(i).append(",10\"");
        }
        directory(directory.append('}').toString());
        for (int i = 0; i < 11; i++) {
            server.expect(requestTo(PolicyCompanyFallbackClient.nodeUrl("new_m" + i)))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertThat(client.fetch(List.of(List.of("机械"))).dataGaps()).anyMatch(gap -> gap.contains("最多11"));
        server.verify();
    }

    @Test
    void preservesSuccessfulIndustrySamplesAndReportsSanitizedPartialFailure() {
        directory("{\"new_jxhy\":\"new_jxhy,机械行业,90\",\"new_jrhy\":\"new_jrhy,金融行业,30\"}");
        server.expect(requestTo(PolicyCompanyFallbackClient.nodeUrl("new_jxhy"))).andRespond(withServerError().body("sensitive body"));
        server.expect(requestTo(PolicyCompanyFallbackClient.nodeUrl("new_jrhy"))).andRespond(withSuccess(
                "[{\"code\":\"600001\",\"symbol\":\"sh600001\",\"name\":\"银行样本\"}]", MediaType.APPLICATION_JSON));
        var result = client.fetch(List.of(List.of("机械"), List.of("金融")));
        assertThat(result.companies()).hasSize(1);
        assertThat(result.dataGaps()).containsExactly("机械行业：备用行业采集失败。");
        server.verify();
    }

    @Test
    void doesNotFetchWhenNoThemeHasEvidence() {
        assertThat(client.fetch(List.of()).companies()).isEmpty();
        server.verify();
    }

    private void directory(String object) {
        server.expect(requestTo(PolicyCompanyFallbackClient.DIRECTORY_URL))
                .andRespond(withSuccess("var S_Finance_bankuai_sinaindustry = " + object + ";", MediaType.APPLICATION_JSON));
    }
}
