package com.aistock.research.integration.yahoo;

import com.aistock.research.config.LiveDataProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Yahoo Finance 公开行情客户端，用 v8/finance/spark 批量拉取日线收盘序列。
 *
 * <p>该源只用于外围看板的日韩行业 ETF（东财不覆盖），需要国际网络可达。
 * 为避免把全局 RestClient 的代理套到国内数据源上，这里单独构建一个
 * RestClient，代理只从 {@code research.live-data.yahoo-proxy-url}（可用
 * 环境变量 YAHOO_PROXY_URL 覆盖）或环境变量 https_proxy 读取；
 * 失败由调用方按数据缺口降级处理。Yahoo 对高频访问限流严格，因此批量
 * 分片、请求间强制间隔，并整体失败才抛异常。
 */
@Component
public class YahooFinanceClient {

    private static final Logger logger = LoggerFactory.getLogger(YahooFinanceClient.class);
    private static final int SYMBOLS_PER_REQUEST = 20;
    private static final int ATTEMPTS = 2;
    private static final long RETRY_SLEEP_MILLIS = 1500;
    private static final long REQUEST_INTERVAL_MILLIS = 600;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LiveDataProperties properties;
    private final Object requestLock = new Object();
    private volatile long lastRequestMillis;

    @Autowired
    public YahooFinanceClient(ObjectMapper objectMapper, LiveDataProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restClient = buildRestClient(properties == null ? null : properties.yahooProxyUrl());
    }

    private static RestClient buildRestClient(String configuredProxyUrl) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        proxyAddress(configuredProxyUrl).ifPresent(address -> httpClientBuilder.proxy(ProxySelector.of(address)));
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        requestFactory.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                .defaultHeader("Accept", "application/json,text/plain,*/*")
                .build();
    }

    /** 优先用专用代理配置（YAHOO_PROXY_URL），否则退回环境变量 https_proxy；都不配则直连。 */
    private static Optional<InetSocketAddress> proxyAddress(String configuredProxyUrl) {
        String proxyUrl = firstNonBlank(
                configuredProxyUrl,
                System.getenv("YAHOO_PROXY_URL"),
                System.getenv("https_proxy"),
                System.getenv("HTTPS_PROXY"),
                System.getenv("http_proxy"),
                System.getenv("HTTP_PROXY")
        );
        if (proxyUrl == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(proxyUrl);
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getPort() < 0) {
                return Optional.empty();
            }
            return Optional.of(new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 批量获取收盘序列并计算最新涨跌幅。返回 symbol -> 行情；
     * 数据源不认识或无数据的 symbol 不会出现在结果里。
     */
    public Map<String, YahooSparkQuote> fetchSparkQuotes(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .toList();
        Map<String, YahooSparkQuote> quotes = new LinkedHashMap<>();
        Exception lastException = null;
        for (int start = 0; start < distinct.size(); start += SYMBOLS_PER_REQUEST) {
            int end = Math.min(start + SYMBOLS_PER_REQUEST, distinct.size());
            List<String> chunk = distinct.subList(start, end);
            String url = sparkUrl(chunk);
            boolean fetched = false;
            for (int attempt = 1; attempt <= ATTEMPTS && !fetched; attempt++) {
                try {
                    for (YahooSparkQuote quote : parseSparkBody(fetchBody(url), objectMapper)) {
                        quotes.put(quote.symbol(), quote);
                    }
                    fetched = true;
                } catch (Exception exception) {
                    lastException = exception;
                    quietSleep(RETRY_SLEEP_MILLIS);
                }
            }
            if (!fetched) {
                logger.warn("Yahoo Finance 批量行情获取失败（{}）：{}", chunk.size(), rootMessage(lastException));
            }
            throttle();
        }
        if (quotes.isEmpty() && lastException != null) {
            throw new IllegalStateException("Yahoo Finance 行情数据获取失败", lastException);
        }
        return quotes;
    }

    private String sparkUrl(List<String> symbols) {
        String base = properties == null || properties.yahooSparkUrl() == null || properties.yahooSparkUrl().isBlank()
                ? LiveDataProperties.DEFAULT_YAHOO_SPARK_URL
                : properties.yahooSparkUrl();
        // 指数代码以 ^ 开头（如 ^KQ11），直接拼进 URL 前预编码
        return base
                + "?symbols=" + String.join(",", symbols).replace("^", "%5E")
                + "&range=5d&interval=1d";
    }

    private String fetchBody(String url) {
        return restClient.get().uri(URI.create(url)).retrieve().body(String.class);
    }

    static List<YahooSparkQuote> parseSparkBody(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Yahoo spark 返回空响应");
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            throw new IllegalStateException("Yahoo spark 返回非 JSON 内容：" + sample(body));
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            List<YahooSparkQuote> quotes = new ArrayList<>();
            // 单 symbol 路径返回 {"spark":{"result":[...]}}，批量 symbols 参数返回 {symbol: {...}} 映射
            JsonNode result = root.path("spark").path("result");
            if (result.isArray()) {
                for (JsonNode entry : result) {
                    parseSparkEntry(entry).ifPresent(quotes::add);
                }
                return quotes;
            }
            if (root.isObject()) {
                for (JsonNode entry : root) {
                    parseSparkEntry(entry).ifPresent(quotes::add);
                }
            }
            return quotes;
        } catch (Exception exception) {
            throw new IllegalStateException("Yahoo spark JSON 解析失败", exception);
        }
    }

    private static Optional<YahooSparkQuote> parseSparkEntry(JsonNode entry) {
        String symbol = entry.path("symbol").asText(null);
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        JsonNode closesNode = entry.path("close");
        JsonNode stamps = entry.path("timestamp");
        if (!closesNode.isArray()) {
            JsonNode response = entry.path("response").path(0);
            closesNode = response.path("indicators").path("quote").path(0).path("close");
            stamps = response.path("timestamp");
        }
        if (!closesNode.isArray()) {
            return Optional.empty();
        }
        List<BigDecimal> closes = new ArrayList<>();
        int lastValidIndex = -1;
        for (int index = 0; index < closesNode.size(); index++) {
            JsonNode close = closesNode.get(index);
            if (close.isNumber()) {
                closes.add(BigDecimal.valueOf(close.asDouble()));
                lastValidIndex = index;
            }
        }
        if (closes.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal last = closes.get(closes.size() - 1);
        BigDecimal changePercent = null;
        if (closes.size() >= 2) {
            BigDecimal previous = closes.get(closes.size() - 2);
            if (previous.compareTo(BigDecimal.ZERO) > 0) {
                changePercent = last.subtract(previous)
                        .divide(previous, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
            }
        }
        Instant lastTradeTime = null;
        if (stamps.isArray() && lastValidIndex >= 0 && lastValidIndex < stamps.size()) {
            long epochSeconds = stamps.get(lastValidIndex).asLong(0);
            if (epochSeconds > 0) {
                lastTradeTime = Instant.ofEpochSecond(epochSeconds);
            }
        }
        return Optional.of(new YahooSparkQuote(symbol, last, changePercent, lastTradeTime));
    }

    private void throttle() {
        synchronized (requestLock) {
            long elapsed = System.currentTimeMillis() - lastRequestMillis;
            if (elapsed < REQUEST_INTERVAL_MILLIS) {
                quietSleep(REQUEST_INTERVAL_MILLIS - elapsed);
            }
            lastRequestMillis = System.currentTimeMillis();
        }
    }

    private static void quietSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String sample(String body) {
        String compact = body.replaceAll("\\s+", " ");
        return compact.length() <= 120 ? compact : compact.substring(0, 120);
    }
}
