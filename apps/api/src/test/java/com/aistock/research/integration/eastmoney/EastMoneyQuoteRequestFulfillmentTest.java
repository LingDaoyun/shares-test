package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EastMoneyQuoteRequestFulfillmentTest {
    private static final String SINA_API =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.";
    private static final List<String> SYMBOLS = List.of("600000", "000001", "920000");

    @Test
    void fullRequestWithEnoughRowsButIncompletePrimaryStillRequestsTheBackup() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IncompletePrimaryClient client = new IncompletePrimaryClient(builder.build());
        expectSymbols(server, "hs_a", "3",
                "[{\"symbol\":\"sh600000\",\"code\":\"600000\"},"
                        + "{\"symbol\":\"sz000001\",\"code\":\"000001\"},"
                        + "{\"symbol\":\"bj920000\",\"code\":\"920000\"}]");
        expectSymbols(server, "sw2_490100", "1",
                "[{\"symbol\":\"sh600000\",\"code\":\"600000\"}]");

        client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(client.primary.fetchedCount()).isEqualTo(client.primary.expectedCount());
        assertThat(client.primary.complete()).isFalse();
        assertThat(client.backupRequestedSymbols).containsExactlyElementsOf(SYMBOLS);
        server.verify();
    }

    @Test
    void satisfiedLimitedRequestKeepsItsIncompletePrimaryWithoutRequestingTheBackup() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IncompletePrimaryClient client = new IncompletePrimaryClient(builder.build());

        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(2);

        assertThat(result).isSameAs(client.primary);
        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.expectedCount()).isEqualTo(3);
        assertThat(result.complete()).isFalse();
        assertThat(client.backupRequestedSymbols).isEmpty();
        server.verify();
    }

    private void expectSymbols(MockRestServiceServer server, String node, String count, String rows) {
        String countUrl = SINA_API + "getHQNodeStockCount?node=" + node;
        server.expect(requestTo(countUrl)).andRespond(withSuccess(count, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SINA_API + "getHQNodeData?node=" + node
                        + "&page=1&num=80&sort=symbol&asc=1"))
                .andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
        server.expect(requestTo(countUrl)).andRespond(withSuccess(count, MediaType.APPLICATION_JSON));
    }

    private static final class IncompletePrimaryClient extends EastMoneyClient {
        private AshareQuoteSnapshot primary;
        private List<String> backupRequestedSymbols = List.of();

        private IncompletePrimaryClient(RestClient restClient) {
            super(restClient, new ObjectMapper(), null);
        }

        @Override
        java.util.Set<String> verifySinaAshareUniverse(java.util.Set<String> symbols) {
            return java.util.Set.of();
        }

        @Override
        AshareQuoteSnapshot fetchEastMoneyQuoteSnapshot(int limit) {
            List<EastMoneyQuote> rows = SYMBOLS.stream().limit(limit).map(this::quote).toList();
            primary = new AshareQuoteSnapshot(rows, limit, SYMBOLS.size(), rows.size(),
                    SYMBOLS.size() - rows.size(), false, "不完整主源", Instant.parse("2026-10-20T07:00:00Z"));
            return primary;
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            backupRequestedSymbols = List.copyOf(symbols);
            return symbols.stream().map(this::quote).toList();
        }

        @Override
        String fetchBodyWithCurl(String url, Exception failure, String referer) {
            throw new AssertionError("Quote fulfillment tests must not access the network: " + url);
        }

        private EastMoneyQuote quote(String symbol) {
            Instant at = Instant.parse("2026-10-20T07:00:00Z");
            return new EastMoneyQuote(symbol, "样本股票", "A股", null,
                    BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                    new BigDecimal("100000000"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                    "测试行情", "https://quote.example/" + symbol, at, LocalDate.of(2026, 10, 20), at);
        }
    }
}
