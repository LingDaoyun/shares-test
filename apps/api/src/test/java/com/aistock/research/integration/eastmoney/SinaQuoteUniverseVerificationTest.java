package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinaQuoteUniverseVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, List<String>> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> countCalls = new HashMap<>();
    private final Map<String, Integer> pageCalls = new HashMap<>();
    private final Map<String, Integer> reportedCounts = new HashMap<>();
    private String changedCountNode;
    private String cancelledNode;
    private String temporarilyIncompleteNode;
    private String persistentlyIncompleteNode;
    private int incompletePage = 1;
    private Client client;

    @BeforeEach
    void setUp() {
        nodes.put("hs_a", List.of("600000", "688001", "000001", "920000"));
        nodes.put("sh_a", List.of("600000", "688001"));
        nodes.put("sz_a", List.of("000001"));
        nodes.put("hs_bjs", List.of("920000"));
        nodes.put("kcb", List.of("688001", "689009"));
        nodes.put("sw2_490100", List.of("601995"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            String node = query.getFirst("node");
            if (node.equals(cancelledNode)) throw new CancellationException("scan cancelled");
            List<String> symbols = nodes.getOrDefault(node, List.of());
            String body;
            if (request.getURI().getPath().endsWith("getHQNodeStockCount")) {
                int calls = countCalls.merge(node, 1, Integer::sum);
                int count = reportedCounts.getOrDefault(node, symbols.size())
                        + (node.equals(changedCountNode) && calls > 1 ? 1 : 0);
                body = Integer.toString(count);
            } else {
                int page = Integer.parseInt(query.getFirst("page"));
                int calls = pageCalls.merge(node + ":" + page, 1, Integer::sum);
                int offset = (page - 1) * 80;
                symbols = symbols.subList(Math.min(offset, symbols.size()), Math.min(offset + 80, symbols.size()));
                if (page == incompletePage && ((node.equals(temporarilyIncompleteNode) && calls == 1)
                        || node.equals(persistentlyIncompleteNode))) {
                    symbols = List.of();
                }
                List<Map<String, String>> rows = symbols.stream()
                        .map(code -> Map.of("code", code, "symbol", prefix(code) + code)).toList();
                body = JSON.writeValueAsString(rows);
            }
            return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
        });
        client = new Client(builder.build());
    }

    @Test
    void verifiedExchangeIdentitiesAndStarCdrRestoreFullBackupCoverage() {
        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(result.complete()).isTrue();
        assertThat(result.expectedCount()).isEqualTo(5);
        assertThat(result.missingCount()).isZero();
        assertThat(client.requestedSymbols).containsExactly("600000", "688001", "000001", "920000", "689009");
        assertThat(countCalls.keySet()).contains("sh_a", "sz_a", "hs_bjs", "kcb");
        assertThat(result.source()).contains("分市场身份核验", "科创CDR", "腾讯");
    }

    @Test
    void sameCountsWithDifferentExchangeIdentitiesStayBlocked() {
        nodes.put("sh_a", List.of("600999", "688001"));
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void aTransientEmptyExchangePageIsFetchedAgainBeforeBlockingTheScan() {
        temporarilyIncompleteNode = "sh_a";

        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(result.complete()).isTrue();
        assertThat(pageCalls.get("sh_a:1")).isEqualTo(2);
        assertThat(countCalls.get("sh_a")).isEqualTo(2);
        assertThat(countCalls.get("hs_a")).isEqualTo(2);
        assertThat(client.requestedSymbols).contains("689009");
    }

    @Test
    void aTransientEmptyStarPageIsFetchedAgainBeforeBlockingTheScan() {
        temporarilyIncompleteNode = "kcb";

        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isTrue();
        assertThat(pageCalls.get("kcb:1")).isEqualTo(2);
        assertThat(countCalls.get("kcb")).isEqualTo(2);
    }

    @Test
    void aPersistentlyEmptyExchangePageIsRetriedOnlyOnceAndStaysBlocked() {
        persistentlyIncompleteNode = "sh_a";

        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
        assertThat(pageCalls.get("sh_a:1")).isEqualTo(2);
        assertThat(client.requestedSymbols).doesNotContain("689009");
    }

    @Test
    void aTransientMissingMiddlePageOfTheMainListRetriesOnlyThatPage() {
        List<String> universe = new ArrayList<>(nodes.get("hs_a"));
        List<String> shanghai = new ArrayList<>(nodes.get("sh_a"));
        for (int i = 1; i <= 161; i++) {
            String symbol = String.format("600%03d", i);
            universe.add(symbol);
            shanghai.add(symbol);
        }
        nodes.put("hs_a", universe);
        nodes.put("sh_a", shanghai);
        temporarilyIncompleteNode = "hs_a";
        incompletePage = 2;

        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(result.complete()).isTrue();
        assertThat(result.fetchedCount()).isEqualTo(166);
        assertThat(pageCalls.get("hs_a:1")).isEqualTo(1);
        assertThat(pageCalls.get("hs_a:2")).isEqualTo(2);
        assertThat(pageCalls.get("hs_a:3")).isEqualTo(1);
        assertThat(countCalls.get("hs_a")).isEqualTo(2);
    }

    @Test
    void aMissingExchangePageStaysBlocked() {
        nodes.put("hs_bjs", List.of());
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void changingExchangeTotalsStayBlocked() {
        changedCountNode = "sh_a";
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void aStockAssignedToTheWrongExchangeStaysBlocked() {
        nodes.put("sh_a", List.of("600000", "000001"));
        nodes.put("sz_a", List.of("688001"));
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void duplicateExchangeIdentitiesStayBlocked() {
        nodes.put("sz_a", List.of("000001", "000001"));
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void extraRowsCannotBeTruncatedToHideAnInvalidExchangeList() {
        nodes.put("sh_a", List.of("600000", "688001", "600999"));
        reportedCounts.put("sh_a", 2);
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void duplicateRowsAfterTheReportedEndCannotBeHidden() {
        nodes.put("sh_a", List.of("600000", "688001", "600000"));
        reportedCounts.put("sh_a", 2);
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void independentlyVerifiedBackupDoesNotInheritAnIncompletePrimaryDenominator() {
        client.partialPrimary = true;
        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);
        assertThat(result.complete()).isTrue();
        assertThat(result.expectedCount()).isEqualTo(5);
        assertThat(result.source()).contains("独立清单核验");
    }

    @Test
    void cancelledExchangeVerificationStopsBeforeRequestingQuotes() {
        cancelledNode = "sh_a";
        assertThatThrownBy(() -> client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE))
                .isInstanceOf(CancellationException.class);
        assertThat(client.requestedSymbols).isEmpty();
    }

    @Test
    void aStarStockMissingFromTheAshareListCannotBeSilentlyAddedAsACdr() {
        nodes.put("kcb", List.of("688001", "688999", "689009"));
        assertThat(client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE).complete()).isFalse();
    }

    @Test
    void missingCdrQuotesKeepCoverageIncomplete() {
        client.missingQuote = "689009";
        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);
        assertThat(result.complete()).isFalse();
        assertThat(result.expectedCount()).isEqualTo(5);
        assertThat(result.missingCount()).isEqualTo(1);
    }

    @Test
    void limitedRequestsDoNotClaimFullCoverageOrLoadAllExchangeLists() {
        AshareQuoteSnapshot result = client.fetchAshareQuoteSnapshot(2);
        assertThat(result.complete()).isFalse();
        assertThat(result.quotes()).hasSize(2);
        assertThat(countCalls.keySet()).doesNotContain("sh_a", "sz_a", "hs_bjs", "kcb");
    }

    private static String prefix(String symbol) {
        return symbol.startsWith("6") ? "sh" : symbol.startsWith("92") ? "bj" : "sz";
    }

    private static final class Client extends EastMoneyClient {
        private List<String> requestedSymbols = new ArrayList<>();
        private String missingQuote;
        private boolean partialPrimary;

        private Client(RestClient restClient) {
            super(restClient, JSON, null);
        }

        @Override
        AshareQuoteSnapshot fetchEastMoneyQuoteSnapshot(int limit) {
            if (partialPrimary) {
                return new AshareQuoteSnapshot(List.of(), limit, 6, 0, 6, false, "主源缺页", Instant.now());
            }
            throw new IllegalStateException("Primary disconnected");
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            requestedSymbols = List.copyOf(symbols);
            return symbols.stream().filter(symbol -> !symbol.equals(missingQuote)).map(symbol -> {
                Instant at = Instant.parse("2026-10-20T07:00:00Z");
                return new EastMoneyQuote(symbol, "样本股票", "A股", null,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                        new BigDecimal("100000000"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "腾讯行情", "https://quote.example/" + symbol, at, LocalDate.of(2026, 10, 20), at);
            }).toList();
        }

        @Override
        String fetchBodyWithCurl(String url, Exception failure, String referer) {
            throw new IllegalStateException("No network in universe tests: " + url);
        }
    }
}
