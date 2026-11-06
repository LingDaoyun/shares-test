package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Bounded concept-directory and single-board membership reads, without retries or hidden fan-out. */
@Component
public class EastMoneyConceptClient {
    private static final String ENDPOINT = "https://push2.eastmoney.com/api/qt/clist/get";
    private static final String BOARD_FIELDS = "f12,f14,f3,f6,f104,f105,f106,f124";
    private static final String MEMBER_FIELDS = "f12,f14,f2,f3,f6,f8,f124";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FETCH_BUDGET = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EastMoneyConceptClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        proxyFromEnvironment(System.getenv()).ifPresent(address -> builder.proxy(ProxySelector.of(address)));
        this.httpClient = builder.build();
    }

    /** Matches RestClientConfig's existing HTTPS/HTTP environment precedence without logging configuration. */
    static Optional<InetSocketAddress> proxyFromEnvironment(Map<String, String> environment) {
        for (String key : List.of("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")) {
            String value = environment.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                URI uri = URI.create(value);
                if (uri.getHost() == null || uri.getHost().isBlank() || uri.getPort() < 0) return Optional.empty();
                return Optional.of(new InetSocketAddress(uri.getHost(), uri.getPort()));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public BoardSnapshot fetchBoards() {
        PageResult<Board> result = fetchAll("m:90 t:3 f:!50", BOARD_FIELDS, this::readBoard, Board::code);
        return new BoardSnapshot(result.fetchedAt(), result.items());
    }

    public MemberSnapshot fetchMembers(String boardCode) {
        if (boardCode == null || !boardCode.matches("BK\\d+")) {
            throw new IllegalArgumentException("概念板块代码无效");
        }
        PageResult<Member> result = fetchAll("b:" + boardCode + " f:!50", MEMBER_FIELDS,
                this::readMember, Member::symbol);
        return new MemberSnapshot(result.fetchedAt(), result.total(), result.items());
    }

    private <T> PageResult<T> fetchAll(String filter, String fields, Function<JsonNode, T> reader,
                                      Function<T, String> identity) {
        long started = nanoTime();
        List<T> items = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Integer expectedTotal = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Duration timeout = remainingRequestTime(started);
            JsonNode root;
            try {
                root = objectMapper.readTree(fetchBody(pageUrl(filter, fields, page), timeout));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("概念行情请求已取消");
            } catch (IOException failure) {
                throw new IllegalStateException("东方财富概念行情获取失败", failure);
            }
            remainingRequestTime(started);
            if (root == null || !root.path("rc").isIntegralNumber() || !root.path("rc").canConvertToInt()
                    || root.path("rc").intValue() != 0) {
                throw new IllegalStateException("概念行情响应状态无效");
            }
            JsonNode data = root.path("data");
            JsonNode totalNode = data.path("total");
            JsonNode diff = data.path("diff");
            if (!data.isObject() || !totalNode.isIntegralNumber() || !totalNode.canConvertToInt()
                    || totalNode.intValue() < 0 || !(diff.isArray() || diff.isObject())) {
                throw new IllegalStateException("概念行情响应结构无效");
            }
            int total = totalNode.intValue();
            if (total > PAGE_SIZE * MAX_PAGES) {
                throw new IllegalStateException("概念行情总数超过1000条读取上限，未返回部分数据");
            }
            if (expectedTotal != null && expectedTotal != total) {
                throw new IllegalStateException("概念行情分页总数发生变化");
            }
            expectedTotal = total;
            int expectedPageSize = Math.min(PAGE_SIZE, total - items.size());
            if (diff.size() != expectedPageSize) {
                throw new IllegalStateException("概念行情分页覆盖不完整：" + diff.size() + "/" + expectedPageSize);
            }
            for (JsonNode item : diff) {
                T parsed = reader.apply(item);
                if (!identities.add(identity.apply(parsed))) {
                    throw new IllegalStateException("概念行情分页存在重复代码：" + identity.apply(parsed));
                }
                items.add(parsed);
            }
            remainingRequestTime(started);
            if (items.size() == total) {
                return new PageResult<>(Instant.now(), total, List.copyOf(items));
            }
        }
        throw new IllegalStateException("概念行情分页超限，未返回部分数据");
    }

    private URI pageUrl(String filter, String fields, int page) {
        return URI.create(ENDPOINT + "?pn=" + page + "&pz=" + PAGE_SIZE
                + "&po=1&np=1&fltt=2&invt=2&fid=f12&ut=bd1d9ddb04089700cf9c27f6f7426281"
                + "&fs=" + URLEncoder.encode(filter, StandardCharsets.UTF_8) + "&fields=" + fields);
    }

    /** One request only; waiting for the complete body is also bounded by the supplied deadline. */
    String fetchBody(URI uri, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json")
                .header("Referer", "https://quote.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                .GET().build();
        var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("概念行情HTTP状态异常：" + response.statusCode());
            }
            return response.body();
        } catch (TimeoutException timeoutFailure) {
            throw new HttpTimeoutException("概念行情单次请求超时");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException ioFailure) throw ioFailure;
            if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IOException("概念行情请求失败", failure.getCause());
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    long nanoTime() {
        return System.nanoTime();
    }

    private Duration remainingRequestTime(long started) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("概念行情请求已取消");
        long remaining = FETCH_BUDGET.toNanos() - (nanoTime() - started);
        if (remaining <= 0) throw new IllegalStateException("概念行情读取超过20秒总预算");
        return Duration.ofNanos(Math.min(remaining, REQUEST_TIMEOUT.toNanos()));
    }

    private Board readBoard(JsonNode item) {
        String code = requiredText(item, "f12");
        if (!code.matches("BK\\d+")) throw new IllegalStateException("概念板块代码无效");
        return new Board(code, requiredText(item, "f14"), decimal(item, "f3"), decimal(item, "f6"),
                count(item, "f104"), count(item, "f105"), count(item, "f106"), marketTimestamp(item));
    }

    private Member readMember(JsonNode item) {
        String symbol = requiredText(item, "f12");
        if (!symbol.matches("\\d{6}")) throw new IllegalStateException("概念成分证券代码无效");
        return new Member(symbol, requiredText(item, "f14"), decimal(item, "f2"), decimal(item, "f3"),
                decimal(item, "f6"), decimal(item, "f8"), marketTimestamp(item));
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || value.asText().isBlank() || "-".equals(value.asText().trim())) {
            throw new IllegalStateException("概念行情代码或名称缺失");
        }
        return value.asText().trim();
    }

    private static BigDecimal decimal(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer count(JsonNode item, String field) {
        BigDecimal value = decimal(item, field);
        if (value == null || value.signum() < 0) return null;
        try {
            return value.intValueExact();
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private static Instant marketTimestamp(JsonNode item) {
        BigDecimal value = decimal(item, "f124");
        if (value == null || value.signum() <= 0) return null;
        try {
            return Instant.ofEpochSecond(value.longValueExact());
        } catch (ArithmeticException | DateTimeException invalid) {
            return null;
        }
    }

    private record PageResult<T>(Instant fetchedAt, int total, List<T> items) { }

    public record BoardSnapshot(Instant fetchedAt, List<Board> boards) {
        public BoardSnapshot { boards = List.copyOf(boards); }
    }

    public record Board(String code, String name, BigDecimal changePercent, BigDecimal amount,
                        Integer advancing, Integer declining, Integer unchanged, Instant marketTimestamp) { }

    public record MemberSnapshot(Instant fetchedAt, int total, List<Member> members) {
        public MemberSnapshot { members = List.copyOf(members); }
    }

    public record Member(String symbol, String name, BigDecimal latestPrice, BigDecimal changePercent,
                         BigDecimal amount, BigDecimal turnoverRate, Instant marketTimestamp) { }
}
