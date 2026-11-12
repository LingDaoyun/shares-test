package com.aistock.research.stocksearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Search needs the complete security directory, not quotes for every security. */
@Component
public class SinaStockDirectoryClient {
    private static final String API = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 160;
    private final ObjectMapper objectMapper;
    private final Function<String, String> fetchBody;

    @Autowired
    public SinaStockDirectoryClient(RestClient restClient, ObjectMapper objectMapper) {
        this(objectMapper, url -> restClient.get().uri(url).retrieve().body(String.class));
    }

    SinaStockDirectoryClient(ObjectMapper objectMapper, Function<String, String> fetchBody) {
        this.objectMapper = objectMapper;
        this.fetchBody = fetchBody;
    }

    public StockDirectorySnapshot fetch() {
        List<String> gaps = new ArrayList<>();
        Node main = new Node("hs_a", readCount("hs_a", gaps));
        Node star = new Node("kcb", readCount("kcb", gaps));
        List<Node> nodes = List.of(main, star);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            Map<Node, List<Future<Page>>> pending = new LinkedHashMap<>();
            for (Node node : nodes) {
                List<Future<Page>> pages = new ArrayList<>();
                int pageCount = (int) Math.min(MAX_PAGES, Math.max(0L, ((long) node.before + PAGE_SIZE - 1) / PAGE_SIZE));
                for (int page = 1; page <= pageCount; page++) {
                    int pageNumber = page;
                    pages.add(executor.submit(() -> readPage(node.name, pageNumber, node.before)));
                }
                pending.put(node, pages);
            }
            for (var item : pending.entrySet()) {
                Node node = item.getKey();
                for (Future<Page> future : item.getValue()) {
                    Page page = future.get();
                    if (!page.valid()) node.valid = false;
                    if (page.gap() != null) gaps.add(page.gap());
                    for (StockDirectorySnapshot.Stock stock : page.stocks()) {
                        if (node.conflicts.contains(stock.symbol())) continue;
                        StockDirectorySnapshot.Stock previous = node.stocks.putIfAbsent(stock.symbol(), stock);
                        if (previous != null) {
                            node.valid = false;
                            if (!previous.equals(stock)) {
                                node.stocks.remove(stock.symbol());
                                node.conflicts.add(stock.symbol());
                            }
                        }
                    }
                }
                node.after = readCount(node.name, gaps);
                if (!node.complete()) gaps.add(node.name + "目录覆盖 " + node.stocks.size() + "/" + Math.max(0, node.before));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("股票目录请求已取消");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("股票目录获取失败", exception.getCause());
        } finally {
            executor.shutdownNow();
        }

        boolean starIdentitiesMatch = starShares(main).equals(starShares(star));
        if (!starIdentitiesMatch) gaps.add("科创板证券身份核验未通过");
        Map<String, StockDirectorySnapshot.Stock> all = new LinkedHashMap<>(main.stocks);
        int addedCdrs = 0;
        for (StockDirectorySnapshot.Stock stock : star.stocks.values()) {
            StockDirectorySnapshot.Stock previous = all.get(stock.symbol());
            if (previous != null && !previous.equals(stock)) gaps.add("证券名称跨目录不一致：" + stock.symbol());
            if (stock.symbol().startsWith("689") && previous == null) {
                all.put(stock.symbol(), stock);
                addedCdrs++;
            }
        }
        if (all.isEmpty()) throw new IllegalStateException("全A股股票目录暂不可用");
        int expectedCount = main.stableCount() && star.complete() ? main.before + addedCdrs : 0;
        boolean complete = main.complete() && star.complete() && gaps.isEmpty()
                && all.size() == expectedCount;
        String source = "新浪沪深京A股目录 + 科创CDR（" + (complete ? "完整" : "不完整")
                + "，" + all.size() + "/" + (expectedCount > 0 ? expectedCount : "总数未确认") + "）";
        if (!gaps.isEmpty()) source += "；" + gaps.stream().distinct().limit(3).collect(Collectors.joining("；"));
        return new StockDirectorySnapshot(List.copyOf(all.values()), expectedCount, complete, source, Instant.now());
    }

    private Set<String> starShares(Node node) {
        return node.stocks.keySet().stream().filter(symbol -> symbol.startsWith("688")).collect(Collectors.toSet());
    }

    private int readCount(String node, List<String> gaps) {
        try {
            int count = objectMapper.readTree(fetchBody.apply(API + "getHQNodeStockCount?node=" + node)).asInt(-1);
            if (count <= 0) throw new IllegalStateException("总数无效");
            return count;
        } catch (RuntimeException | java.io.IOException exception) {
            gaps.add(node + "证券总数不可用");
            return -1;
        }
    }

    private Page readPage(String node, int page, int total) {
        try {
            JsonNode rows = objectMapper.readTree(fetchBody.apply(API + "getHQNodeData?node=" + node
                    + "&page=" + page + "&num=" + PAGE_SIZE + "&sort=symbol&asc=1"));
            if (rows == null || !rows.isArray()) throw new IllegalStateException("目录格式无效");
            boolean valid = rows.size() == Math.min(PAGE_SIZE, total - (page - 1) * PAGE_SIZE);
            List<StockDirectorySnapshot.Stock> stocks = new ArrayList<>();
            for (JsonNode row : rows) {
                String symbol = row.path("code").asText("");
                String name = row.path("name").asText("").strip();
                String marketCode = marketCode(symbol);
                boolean identityValid = marketCode != null && !name.isEmpty()
                        && (marketCode + symbol).equals(row.path("symbol").asText(""))
                        && (!"kcb".equals(node) || symbol.startsWith("688") || symbol.startsWith("689"));
                if (!identityValid) { valid = false; continue; }
                String market = switch (marketCode) { case "sh" -> "上交所"; case "sz" -> "深交所"; default -> "北交所"; };
                stocks.add(new StockDirectorySnapshot.Stock(symbol, name, market));
            }
            return new Page(stocks, valid, valid ? null : node + "第" + page + "页缺行或证券身份无效");
        } catch (RuntimeException | java.io.IOException exception) {
            return new Page(List.of(), false, node + "第" + page + "页获取失败");
        }
    }

    private String marketCode(String symbol) {
        if (symbol.matches("6\\d{5}")) return "sh";
        if (symbol.matches("[03]\\d{5}")) return "sz";
        if (symbol.matches("(?:[48]\\d{5}|92\\d{4})")) return "bj";
        return null;
    }

    private record Page(List<StockDirectorySnapshot.Stock> stocks, boolean valid, String gap) {}

    private static final class Node {
        private final String name;
        private final int before;
        private int after;
        private boolean valid = true;
        private final Map<String, StockDirectorySnapshot.Stock> stocks = new LinkedHashMap<>();
        private final Set<String> conflicts = new HashSet<>();
        private Node(String name, int before) { this.name = name; this.before = before; }
        private boolean stableCount() { return before > 0 && before == after; }
        private boolean complete() { return valid && stableCount() && stocks.size() == before; }
    }
}
