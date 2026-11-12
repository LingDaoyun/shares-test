package com.aistock.research.stocksearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SinaStockDirectoryClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsAllHundredRowPagesAndAddsStarCdrWithoutDuplicatingStarShares() {
        FakeApi api = api(120);
        StockDirectorySnapshot snapshot = new SinaStockDirectoryClient(JSON, api).fetch();

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.stocks()).hasSize(122);
        assertThat(snapshot.expectedCount()).isEqualTo(122);
        assertThat(snapshot.stocks()).extracting(StockDirectorySnapshot.Stock::symbol)
                .contains("600119", "688001", "689009").doesNotHaveDuplicates();
        assertThat(snapshot.stocks()).extracting(StockDirectorySnapshot.Stock::name).contains("东方119");
        assertThat(api.pageCalls.get()).isEqualTo(3);
        assertThat(api.countCalls.get("hs_a").get()).isEqualTo(2);
        assertThat(api.countCalls.get("kcb").get()).isEqualTo(2);
    }

    @Test
    void limitsParallelPageRequestsToSix() {
        FakeApi api = api(650);
        api.delayMillis = 40;
        StockDirectorySnapshot snapshot = new SinaStockDirectoryClient(JSON, api).fetch();

        assertThat(snapshot.complete()).isTrue();
        assertThat(api.maxActive.get()).isEqualTo(6);
        assertThat(api.pageCalls.get()).isEqualTo(8);
    }

    @Test
    void preservesAvailableRowsButMarksMissingPagesIncomplete() {
        FakeApi api = api(120);
        api.failedPage = "hs_a:1";
        StockDirectorySnapshot snapshot = new SinaStockDirectoryClient(JSON, api).fetch();

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.expectedCount()).isEqualTo(122);
        assertThat(snapshot.stocks()).extracting(StockDirectorySnapshot.Stock::symbol).contains("600119", "689009");
        assertThat(snapshot.source()).contains("不完整");
    }

    @Test
    void rejectsShortPagesDuplicatesAndWrongMarketIdentitiesAsCompleteCoverage() {
        FakeApi shortPage = api(120);
        shortPage.rows.get("hs_a").remove(0);
        assertThat(new SinaStockDirectoryClient(JSON, shortPage).fetch().complete()).isFalse();

        FakeApi duplicate = api(120);
        duplicate.rows.get("hs_a").set(1, duplicate.rows.get("hs_a").get(0));
        StockDirectorySnapshot duplicated = new SinaStockDirectoryClient(JSON, duplicate).fetch();
        assertThat(duplicated.complete()).isFalse();
        assertThat(duplicated.stocks()).extracting(StockDirectorySnapshot.Stock::symbol).doesNotHaveDuplicates();

        FakeApi wrongMarket = api(120);
        wrongMarket.rows.get("hs_a").set(0, row("sz600000", "600000", "东方错误市场"));
        StockDirectorySnapshot invalid = new SinaStockDirectoryClient(JSON, wrongMarket).fetch();
        assertThat(invalid.complete()).isFalse();
        assertThat(invalid.stocks()).extracting(StockDirectorySnapshot.Stock::symbol).doesNotContain("600000");
    }

    @Test
    void rejectsChangingCountsAndMismatchedStarSetsAsCompleteCoverage() {
        FakeApi changing = api(120);
        changing.finalCounts.put("hs_a", 122);
        StockDirectorySnapshot changed = new SinaStockDirectoryClient(JSON, changing).fetch();
        assertThat(changed.complete()).isFalse();
        assertThat(changed.expectedCount()).isZero();

        FakeApi starMismatch = api(120);
        starMismatch.rows.get("kcb").set(0, row("sh688002", "688002", "东方科创二"));
        assertThat(new SinaStockDirectoryClient(JSON, starMismatch).fetch().complete()).isFalse();
    }

    @Test
    void unavailableDirectoryDoesNotProduceASuccessfulEmptySearch() {
        assertThatThrownBy(() -> new SinaStockDirectoryClient(JSON, url -> {
            throw new IllegalStateException("目录不可用");
        }).fetch()).isInstanceOf(IllegalStateException.class).hasMessageContaining("目录");
    }

    private static FakeApi api(int normalCount) {
        List<Map<String, String>> main = new ArrayList<>(IntStream.range(0, normalCount)
                .mapToObj(index -> row("sh" + (600000 + index), String.valueOf(600000 + index), "东方" + index))
                .toList());
        main.add(row("sh688001", "688001", "东方科创"));
        return new FakeApi(main, new ArrayList<>(List.of(row("sh688001", "688001", "东方科创"),
                row("sh689009", "689009", "东方CDR"))));
    }

    private static Map<String, String> row(String marketSymbol, String code, String name) {
        return Map.of("symbol", marketSymbol, "code", code, "name", name);
    }

    private static final class FakeApi implements Function<String, String> {
        private final Map<String, List<Map<String, String>>> rows = new HashMap<>();
        private final Map<String, Integer> initialCounts = new HashMap<>();
        private final Map<String, Integer> finalCounts = new HashMap<>();
        private final Map<String, AtomicInteger> countCalls = new ConcurrentHashMap<>();
        private final AtomicInteger pageCalls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private String failedPage;
        private int delayMillis;

        FakeApi(List<Map<String, String>> main, List<Map<String, String>> star) {
            rows.put("hs_a", main);
            rows.put("kcb", star);
            initialCounts.put("hs_a", main.size());
            initialCounts.put("kcb", star.size());
        }

        @Override
        public String apply(String url) {
            Map<String, String> query = new HashMap<>();
            Arrays.stream(URI.create(url).getQuery().split("&")).forEach(part -> {
                String[] pair = part.split("=");
                query.put(pair[0], pair[1]);
            });
            String node = query.get("node");
            if (url.contains("getHQNodeStockCount")) {
                int call = countCalls.computeIfAbsent(node, ignored -> new AtomicInteger()).incrementAndGet();
                return String.valueOf(call == 1 ? initialCounts.get(node) : finalCounts.getOrDefault(node, initialCounts.get(node)));
            }
            assertThat(query.get("num")).isEqualTo("100");
            int page = Integer.parseInt(query.get("page"));
            pageCalls.incrementAndGet();
            int inFlight = active.incrementAndGet();
            maxActive.accumulateAndGet(inFlight, Math::max);
            try {
                if ((node + ":" + page).equals(failedPage)) throw new IllegalStateException("本页不可用");
                if (delayMillis > 0) Thread.sleep(delayMillis);
                List<Map<String, String>> all = rows.get(node);
                int start = Math.min((page - 1) * 100, all.size());
                return JSON.writeValueAsString(all.subList(start, Math.min(start + 100, all.size())));
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
