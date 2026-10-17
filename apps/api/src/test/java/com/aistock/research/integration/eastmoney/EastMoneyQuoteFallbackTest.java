package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EastMoneyQuoteFallbackTest {

    private static final String COUNT_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeStockCount?node=hs_a";
    private static final String PAGE_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData?node=hs_a&page=1&num=80&sort=symbol&asc=1";
    private static final String SECURITIES_COUNT_URL = COUNT_URL.replace("hs_a", "sw2_490100");
    private static final String SECURITIES_PAGE_URL = PAGE_URL.replace("hs_a", "sw2_490100");
    private static final String SECURITIES_SYMBOLS = "[{\"symbol\":\"sz300059\",\"code\":\"300059\"},{\"symbol\":\"sh601995\",\"code\":\"601995\"}]";
    private static final String ALL_SYMBOLS = "[{\"symbol\":\"sh600000\",\"code\":\"600000\"},{\"symbol\":\"sz000001\",\"code\":\"000001\"},{\"symbol\":\"bj920000\",\"code\":\"920000\"}]";
    private MockRestServiceServer server;
    private FallbackClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FallbackClient(builder.build());
    }

    @Test
    void disconnectedEastMoneyFallsBackToTheWholeSinaUniverseAndTencentQuotes() {
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(client.requestedSymbols).containsExactly("600000", "000001", "920000");
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.missingCount()).isZero();
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.source()).contains("新浪hs_a清单范围", "腾讯", "本清单3/3", "非东财全集核验");
        assertThat(snapshot.quotes()).allSatisfy(quote -> {
            assertThat(quote.marketTimestamp()).isEqualTo(Instant.parse("2026-10-08T07:00:00Z"));
            assertThat(quote.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        });
        server.verify();
    }

    @Test
    void missingTencentRowsKeepTheSnapshotIncomplete() {
        expectUniverse(ALL_SYMBOLS, 3);
        client.tencentRows = client.tencentRows.subList(0, 2);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void duplicateSinaCodesAndUnrequestedTencentRowsCannotFillCoverageGaps() {
        expectUniverse("[{\"symbol\":\"sh600000\",\"code\":\"600000\"},{\"symbol\":\"sh600000\",\"code\":\"600000\"},{\"symbol\":\"sz000001\",\"code\":\"000001\"}]", 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(client.requestedSymbols).containsExactly("600000", "000001");
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600000", "000001");
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void changingSinaTotalsCannotConfirmWholeMarketCoverage() {
        expectUniverse(ALL_SYMBOLS, 4);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.expectedCount()).isZero();
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void aLimitedRequestDoesNotClaimWholeMarketCoverage() {
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(2);

        assertThat(client.requestedSymbols).containsExactly("600000", "000001");
        assertThat(snapshot.quotes()).hasSize(2);
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void successfulEastMoneyDoesNotRequestTheBackupSource() {
        client.eastMoneyAvailable = true;

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(3);

        assertThat(snapshot.complete()).isTrue();
        assertThat(client.requestedSymbols).isEmpty();
        server.verify();
    }

    @Test
    void nonEmptyIncompleteEastMoneySnapshotUsesTheCompleteBackupWithoutMixingRows() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600999"));
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(client.requestedSymbols).containsExactly("600000", "000001", "920000");
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600000", "000001", "920000");
        assertThat(snapshot.source()).contains("新浪", "腾讯");
        server.verify();
    }

    @Test
    void backupFailurePreservesTheNonEmptyPrimarySnapshotAsIncomplete() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600999"));
        expectUniverseRequests(ALL_SYMBOLS, 3);
        expectSecurities("[{\"symbol\":\"sz300059\",\"code\":\"300059\"}]", 2);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(1);
        assertThat(snapshot.missingCount()).isEqualTo(2);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600999");
        assertThat(snapshot.source()).contains("备用获取失败", "主源不完整");
        server.verify();
    }

    @Test
    void twoIncompleteSourcesKeepTheBetterCoveredBackupWithoutCombiningRows() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600999"));
        client.tencentRows = List.of(quote("600000"), quote("000001"));
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600000", "000001");
        assertThat(snapshot.source()).contains("主源覆盖1/3", "备用覆盖2/3", "保留备用不完整");
        server.verify();
    }

    @Test
    void worseIncompleteBackupDoesNotDiscardTheBetterCoveredPrimary() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600998"), quote("600999"));
        client.tencentRows = List.of(quote("600000"));
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600998", "600999");
        assertThat(snapshot.source()).contains("主源覆盖2/3", "备用覆盖1/3", "保留主源不完整");
        server.verify();
    }

    @Test
    void aBackupWithUnknownUniverseCannotReplacePrimaryCoverageWithAKnownDenominator() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600999"));
        expectUniverse(ALL_SYMBOLS, 4);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600999");
        assertThat(snapshot.source()).contains("备用覆盖3/0", "保留主源不完整");
        server.verify();
    }

    @Test
    void differentKnownMarketCountsKeepTheBackupScopeButCannotConfirmThePrimaryUniverse() {
        client.eastMoneyAvailable = true;
        client.eastMoneyTotal = 4;
        client.eastMoneyRows = List.of(quote("600999"));
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.missingCount()).isZero();
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600000", "000001", "920000");
        assertThat(snapshot.source()).contains("新浪hs_a清单范围", "东财4", "新浪hs_a3", "范围差异未核实");
        server.verify();
    }

    @Test
    void aSatisfiedLimitedPrimaryRequestDoesNotFetchTheBackupOrClaimCompleteCoverage() {
        client.eastMoneyAvailable = true;

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(2);

        assertThat(client.requestedSymbols).isEmpty();
        assertThat(snapshot.requestedCount()).isEqualTo(2);
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void aPrematurelyTruncatedLimitedRequestStillUsesTheBackupWithoutClaimingWholeMarketCoverage() {
        client.eastMoneyAvailable = true;
        client.eastMoneyRows = List.of(quote("600999"));
        expectUniverse(ALL_SYMBOLS, 3);

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(2);

        assertThat(client.requestedSymbols).containsExactly("600000", "000001");
        assertThat(snapshot.requestedCount()).isEqualTo(2);
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
        server.verify();
    }

    @Test
    void backupClassifiesSecuritiesStocksEvenWhenTheirNamesDoNotMentionSecurities() {
        expectUniverseRequests("[{\"symbol\":\"sz300059\",\"code\":\"300059\"},{\"symbol\":\"sh601995\",\"code\":\"601995\"},{\"symbol\":\"sh600000\",\"code\":\"600000\"}]", 3);
        expectSecurities(SECURITIES_SYMBOLS, 2);
        client.tencentRows = List.of(quote("300059"), quote("601995"), quote("600000"));

        AshareQuoteSnapshot snapshot = client.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.quotes()).filteredOn(quote -> !quote.symbol().equals("600000"))
                .allSatisfy(quote -> assertThat(quote.industry()).isEqualTo("证券"));
        assertThat(snapshot.quotes()).filteredOn(quote -> quote.symbol().equals("600000"))
                .singleElement().satisfies(quote -> assertThat(quote.industry()).isNull());
        server.verify();
    }

    @Test
    void incompleteSecuritiesClassificationBlocksTheBackup() {
        expectUniverseRequests(ALL_SYMBOLS, 3);
        expectSecurities("[{\"symbol\":\"sz300059\",\"code\":\"300059\"}]", 2);

        assertThatThrownBy(() -> client.fetchAshareQuoteSnapshot(6000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("证券行业清单不完整");
        assertThat(client.requestedSymbols).isEmpty();
        server.verify();
    }

    @Test
    void changingSecuritiesClassificationBlocksTheBackup() {
        expectUniverseRequests(ALL_SYMBOLS, 3);
        expectSecurities(SECURITIES_SYMBOLS, 3);

        assertThatThrownBy(() -> client.fetchAshareQuoteSnapshot(6000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("证券行业清单不完整");
        assertThat(client.requestedSymbols).isEmpty();
        server.verify();
    }

    @Test
    void tencentQuotePreservesMarketCapsAndVolumeRatioInTheirActualUnits() {
        String[] fields = new String[55];
        Arrays.fill(fields, "");
        fields[1] = "浦发银行";
        fields[2] = "600000";
        fields[3] = "9.70";
        fields[30] = "20261008150000";
        fields[37] = "110698";
        fields[44] = "3230.67";
        fields[45] = "3230.68";
        fields[49] = "1.28";

        EastMoneyQuote quote = client.readTencentQuote(
                "v_sh600000=\"" + String.join("~", fields) + "\";", Instant.now()).orElseThrow();

        assertThat(quote.amount()).isEqualByComparingTo("1106980000");
        assertThat(quote.circulatingMarketValue()).isEqualByComparingTo("323067000000");
        assertThat(quote.totalMarketValue()).isEqualByComparingTo("323068000000");
        assertThat(quote.realtimeVolumeRatio()).isEqualByComparingTo("1.28");
    }

    private void expectUniverse(String rows, int finalCount) {
        expectUniverseRequests(rows, finalCount);
        expectSecurities(SECURITIES_SYMBOLS, 2);
    }

    private void expectUniverseRequests(String rows, int finalCount) {
        server.expect(requestTo(COUNT_URL)).andRespond(withSuccess("\"3\"", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PAGE_URL)).andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
        server.expect(requestTo(COUNT_URL)).andRespond(withSuccess("\"" + finalCount + "\"", MediaType.APPLICATION_JSON));
    }

    private void expectSecurities(String rows, int finalCount) {
        server.expect(requestTo(SECURITIES_COUNT_URL)).andRespond(withSuccess("\"2\"", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SECURITIES_PAGE_URL)).andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SECURITIES_COUNT_URL)).andRespond(withSuccess("\"" + finalCount + "\"", MediaType.APPLICATION_JSON));
    }

    private static EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(symbol, "样本股票", "A股", null,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                new BigDecimal("100000000"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                "腾讯行情", "https://qt.gtimg.cn/q=" + symbol,
                Instant.parse("2026-10-08T07:01:00Z"), LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-08T07:00:00Z"));
    }

    private static final class FallbackClient extends EastMoneyClient {
        private List<EastMoneyQuote> tencentRows = List.of(quote("600000"), quote("000001"), quote("920000"));
        private List<String> requestedSymbols = List.of();
        private boolean eastMoneyAvailable;
        private List<EastMoneyQuote> eastMoneyRows;
        private int eastMoneyTotal = 3;

        private FallbackClient(RestClient restClient) {
            super(restClient, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (!eastMoneyAvailable) throw new IllegalStateException("curl: (56) unexpected eof while reading");
            return new AshareQuotePage(eastMoneyTotal, pageNumber == 1
                    ? eastMoneyRows == null ? tencentRows : eastMoneyRows : List.of());
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            requestedSymbols = List.copyOf(symbols);
            return tencentRows;
        }
    }
}
