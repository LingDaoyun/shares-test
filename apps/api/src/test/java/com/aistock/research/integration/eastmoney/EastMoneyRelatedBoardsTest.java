package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyRelatedBoardsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void relatedBoardArrayRetainsSupplierNamesAndDeduplicatesCodes() throws Exception {
        EastMoneyClient client = fixtureClient("""
                {"rc":0,"data":{"total":3,"diff":[
                  {"f12":"BK0855","f14":"半导体"},
                  {"f12":"BK0900","f14":"供应商主题"},
                  {"f12":"BK0855","f14":"半导体"}]}}
                """);

        assertThat(client.fetchStockRelatedBoards("600100")).containsExactly(
                new EastMoneyRelatedBoard("BK0855", "半导体"),
                new EastMoneyRelatedBoard("BK0900", "供应商主题"));
    }

    @Test
    void relatedBoardObjectUsesTheSameCompleteResponseRules() throws Exception {
        EastMoneyClient client = fixtureClient("""
                {"rc":0,"data":{"total":1,"diff":{"0":{"f12":"BK0855","f14":"半导体"}}}}
                """);

        assertThat(client.fetchStockRelatedBoards("600100"))
                .containsExactly(new EastMoneyRelatedBoard("BK0855", "半导体"));
    }

    @Test
    void onlyAnExplicitCompleteEmptyResponseBecomesAnEmptyBoardList() throws Exception {
        assertThat(fixtureClient("{\"rc\":0,\"data\":{\"total\":0,\"diff\":[]}}")
                .fetchStockRelatedBoards("600100")).isEmpty();
        for (String body : List.of(
                "{\"rc\":0,\"data\":null}",
                "{\"rc\":0,\"data\":{\"diff\":[]}}",
                "{\"rc\":0,\"data\":{\"total\":2,\"diff\":[{\"f12\":\"BK0855\",\"f14\":\"半导体\"}]}}",
                "{\"rc\":0,\"data\":{\"total\":1,\"diff\":[]}}")) {
            EastMoneyClient client = fixtureClient(body);
            assertThatThrownBy(() -> client.fetchStockRelatedBoards("600100"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("所属板块");
        }
    }

    @Test
    void invalidBoardIdentityOrConflictingDuplicateCannotBecomeAConfirmedList() throws Exception {
        for (String diff : List.of(
                "[{\"f12\":\"GC001\",\"f14\":\"非板块\"}]",
                "[{\"f12\":\"BKbad\",\"f14\":\"错误代码\"}]",
                "[{\"f12\":\"BK0855\"}]",
                "[{\"f12\":\"BK0855\",\"f14\":\"半导体\"},{\"f12\":\"BK0855\",\"f14\":\"其他名称\"}]")) {
            int total = mapper.readTree(diff).size();
            EastMoneyClient client = fixtureClient("{\"rc\":0,\"data\":{\"total\":" + total + ",\"diff\":" + diff + "}}");
            assertThatThrownBy(() -> client.fetchStockRelatedBoards("600100"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("所属板块");
        }
    }

    @Test
    void invalidStockCodeDoesNotReachTheQuoteTransport() {
        EastMoneyClient client = new EastMoneyClient(RestClient.create(), mapper, null) {
            @Override JsonNode fetchQuoteRoot(String url) {
                throw new AssertionError("invalid symbol must not request a URL");
            }
        };

        assertThatThrownBy(() -> client.fetchStockRelatedBoards("600100&extra=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EastMoneyClient fixtureClient(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        return new EastMoneyClient(RestClient.create(), mapper, null) {
            @Override JsonNode fetchQuoteRoot(String url) {
                assertThat(url).contains("/api/qt/slist/get?secid=1.600100", "spt=3", "pi=0", "pz=1000",
                        "po=1", "invt=2", "fltt=2", "fields=f12,f14");
                return root;
            }
        };
    }
}
