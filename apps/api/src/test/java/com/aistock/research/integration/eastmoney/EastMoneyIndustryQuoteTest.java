package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyIndustryQuoteTest {
    @Test
    void aFailedDirectoryPageStopsBeforeRequestingUnusableLaterPages() {
        List<Integer> pages = new ArrayList<>();
        EastMoneyClient client = directoryClient(pages, true, false);

        assertThatThrownBy(client::fetchIndustryBoards).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行业板块列表获取失败");
        assertThat(pages).containsExactly(1);
    }

    @Test
    void aFailedLaterDirectoryPageCannotCacheAnIncompleteIndustryList() {
        List<Integer> pages = new ArrayList<>();
        EastMoneyClient client = directoryClient(pages, false, true);

        assertThatThrownBy(client::fetchIndustryBoards).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行业板块列表获取失败");
        assertThat(pages).containsExactly(1, 2);
    }

    @Test
    void industryDirectoryAcceptsTheObjectFormatSupportedByTheQuoteTransport() {
        EastMoneyClient client = new EastMoneyClient(RestClient.create(), new ObjectMapper(), null) {
            @Override JsonNode fetchIndustryBoardPage(int page, int size) {
                return new ObjectMapper().createObjectNode().set("data", new ObjectMapper().createObjectNode()
                        .put("total", 1).set("diff", new ObjectMapper().createObjectNode()
                                .set("0", new ObjectMapper().createObjectNode().put("f12", "BK0855").put("f14", "半导体"))));
            }
        };
        assertThat(client.fetchIndustryBoards()).containsExactly(new EastMoneyIndustryBoard("BK0855", "半导体"));
    }

    private static EastMoneyClient directoryClient(List<Integer> pages, boolean failFirst, boolean failSecond) {
        return new EastMoneyClient(RestClient.create(), new ObjectMapper(), null) {
            @Override JsonNode fetchIndustryBoardPage(int page, int size) {
                pages.add(page);
                if (failFirst || failSecond && page == 2) throw new IllegalStateException("网络失败");
                return new ObjectMapper().createObjectNode().set("data", new ObjectMapper().createObjectNode()
                        .put("total", 2).set("diff", new ObjectMapper().createArrayNode()
                                .add(new ObjectMapper().createObjectNode().put("f12", "BK0855").put("f14", "半导体"))));
            }
        };
    }

    @Test
    void industryQuoteFormatMatchesTheSharedPriceDecoder() {
        Client client = new Client(List.of());
        assertThat(client.industryQuotePageUrl("BK0855", 2, 100)).contains("fltt=0", "fid=f3", "pn=2", "pz=100");
        EastMoneyQuote quote = client.readQuote(new ObjectMapper().createObjectNode()
                .put("f12", "600100").put("f14", "样本").put("f13", 1)
                .put("f2", 1250).put("f3", 950).put("f8", 342), Instant.now()).orElseThrow();
        assertThat(quote.latestPrice()).isEqualByComparingTo("12.50");
        assertThat(quote.changePercent()).isEqualByComparingTo("9.50");
        assertThat(quote.turnoverRate()).isEqualByComparingTo("3.42");
    }

    @Test
    void collectsTheWholeIndustryAcrossProviderPages() {
        Client client = new Client(List.of(
                new AshareQuotePage(105, quotes(0, 100)), new AshareQuotePage(105, quotes(100, 5))));

        AshareQuoteSnapshot snapshot = client.fetchIndustryBoardQuoteSnapshot("半导体");

        assertThat(client.pages).containsExactly(1, 2);
        assertThat(snapshot.fetchedCount()).isEqualTo(105);
        assertThat(snapshot.expectedCount()).isEqualTo(105);
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void inconsistentTotalsCannotConfirmIndustryCoverage() {
        Client client = new Client(List.of(new AshareQuotePage(3, quotes(0, 2)),
                new AshareQuotePage(4, quotes(2, 2)), new AshareQuotePage(4, List.of())));

        assertThat(client.fetchIndustryBoardQuoteSnapshot("半导体").complete()).isFalse();
    }

    @Test
    void conflictingDuplicatesCannotFillCoverage() {
        EastMoneyQuote first = quotes(0, 1).get(0);
        EastMoneyQuote changed = new EastMoneyQuote(first.symbol(), first.name(), "A股", "半导体",
                new BigDecimal("11"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, null, null, null, "东方财富", "", first.fetchedAt());
        Client client = new Client(List.of(new AshareQuotePage(3, List.of(first, quotes(1, 1).get(0))),
                new AshareQuotePage(3, List.of(changed, quotes(2, 1).get(0))), new AshareQuotePage(3, List.of())));

        AshareQuoteSnapshot snapshot = client.fetchIndustryBoardQuoteSnapshot("半导体");

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).doesNotContain(first.symbol());
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void anUnknownIndustryDoesNotBecomeAnEmptyCompleteSnapshot() {
        assertThatThrownBy(() -> new Client(List.of()).fetchIndustryBoardQuoteSnapshot("不存在的行业"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("行业板块");
    }

    private static List<EastMoneyQuote> quotes(int start, int count) {
        return IntStream.range(start, start + count).mapToObj(i -> new EastMoneyQuote(
                String.format("600%03d", i), "样本" + i, "A股", "半导体", BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, null, null,
                null, "东方财富", "", Instant.parse("2026-10-08T07:00:00Z"))).toList();
    }

    private static class Client extends EastMoneyClient {
        private final List<AshareQuotePage> responses;
        private final List<Integer> pages = new ArrayList<>();
        Client(List<AshareQuotePage> responses) {
            super(RestClient.create(), new ObjectMapper(), null);
            this.responses = responses;
        }
        @Override public List<EastMoneyIndustryBoard> fetchIndustryBoards() {
            return List.of(new EastMoneyIndustryBoard("BK0855", "半导体"));
        }
        @Override AshareQuotePage fetchIndustryBoardQuotePage(String code, int page, int size) {
            assertThat(code).isEqualTo("BK0855");
            assertThat(size).isEqualTo(100);
            pages.add(page);
            return page <= responses.size() ? responses.get(page - 1) : new AshareQuotePage(0, List.of());
        }
    }
}
