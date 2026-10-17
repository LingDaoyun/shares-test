package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyStockIndustryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EastMoneyClient client = new EastMoneyClient(RestClient.create(), mapper, null);

    @Test
    void acceptsIndustryOnlyForTheRequestedStock() throws Exception {
        assertThat(client.readStockBoardIndustry("002129", mapper.readTree(
                "{\"f57\":\"002129\",\"f58\":\"样本\",\"f127\":\" 光伏设备 \"}")))
                .isEqualTo("光伏设备");
    }

    @Test
    void rejectsAnotherStocksIndustry() throws Exception {
        var data = mapper.readTree("{\"f57\":\"002400\",\"f127\":\"文化传媒\"}");
        assertThatThrownBy(() -> client.readStockBoardIndustry("002129", data))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("证券代码不匹配");
    }

    @Test
    void rejectsIndustryWithoutAConfirmedStockIdentity() throws Exception {
        var data = mapper.readTree("{\"f127\":\"光伏设备\"}");
        assertThatThrownBy(() -> client.readStockBoardIndustry("002129", data))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("证券代码不匹配");
    }

    @Test
    void absentIndustryRemainsUnknown() throws Exception {
        assertThat(client.readStockBoardIndustry("002129", mapper.readTree("{\"f57\":\"002129\"}"))).isNull();
        assertThat(client.readStockBoardIndustry("002129", mapper.readTree("{\"f57\":\"002129\",\"f127\":\"-\"}"))).isNull();
    }

    @Test
    void acceptsTheMatchingCompanySurveyWithoutChangingItsIndustryClassification() throws Exception {
        assertThat(client.readCompanySurveyIndustry("002129", mapper.readTree(
                "{\"SECURITY_CODE\":\"002129\",\"EM2016\":\"制造业-电源设备-光伏设备\"}")))
                .isEqualTo("电源设备");
        assertThat(client.readCompanySurveyIndustry("002129", mapper.readTree(
                "{\"SECURITY_CODE\":\"002129\",\"INDUSTRYCSRC1\":\"制造业-电气机械\"}")))
                .isEqualTo("电气机械");
    }

    @Test
    void rejectsACompanySurveyFromAnotherOrUnidentifiedStock() throws Exception {
        for (String json : new String[] {
                "{\"SECURITY_CODE\":\"002400\",\"EM2016\":\"制造业-电源设备\"}",
                "{\"EM2016\":\"制造业-电源设备\"}"}) {
            var data = mapper.readTree(json);
            assertThatThrownBy(() -> client.readCompanySurveyIndustry("002129", data))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("证券代码不匹配");
        }
    }
}
