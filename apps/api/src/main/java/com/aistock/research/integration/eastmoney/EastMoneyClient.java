package com.aistock.research.integration.eastmoney;

import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.history.KlineHistoryRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class EastMoneyClient {

    private static final Logger logger = LoggerFactory.getLogger(EastMoneyClient.class);
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final String QUOTE_FIELDS = "f2,f3,f5,f6,f8,f9,f12,f13,f14,f23,f60,f100,f115,f124";
    private static final ZoneId CHINA_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TENCENT_QUOTE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String FUND_FLOW_FIELDS = "f12,f13,f14,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87,f124";
    private static final String FUND_FLOW_MINUTE_FIELDS = "f51,f52,f53,f54,f55,f56,f57";
    private static final String FUND_FLOW_DAY_FIELDS = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65";
    private static final String EASTMONEY_FUND_FLOW_DAY_URL = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get";
    private static final String INDUSTRY_BOARD_FIELDS = "f12,f14";
    private static final String A_SHARE_FILTER = "m:0+t:6,m:0+t:80,m:0+t:81,m:1+t:2,m:1+t:23";
    private static final String INDUSTRY_BOARD_FILTER = "m:90 t:2 f:!50";
    private static final int EASTMONEY_ATTEMPTS = 1;
    private static final int EASTMONEY_REMOTE_ATTEMPTS = 3;
    private static final int GENERIC_REMOTE_ATTEMPTS = 2;
    private static final long EASTMONEY_MIN_INTERVAL_MILLIS = 650;
    private static final List<String> EASTMONEY_PUSH2_HOSTS = List.of(
            "push2delay.eastmoney.com",
            "push2.eastmoney.com",
            "17.push2.eastmoney.com",
            "29.push2.eastmoney.com",
            "73.push2.eastmoney.com",
            "91.push2.eastmoney.com"
    );
    private static final long INDUSTRY_BOARD_CACHE_SECONDS = 3600;
    private static final long INDUSTRY_CONSTITUENT_CACHE_SECONDS = 300;
    private static final String FINANCIAL_COLUMNS = String.join(",",
            "SECURITY_CODE",
            "SECURITY_NAME_ABBR",
            "REPORTDATE",
            "DATATYPE",
            "WEIGHTAVG_ROE",
            "MGJYXJJE",
            "XSMLL",
            "YSTZ",
            "SJLTZ",
            "BASIC_EPS",
            "BPS",
            "SECUCODE"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LiveDataProperties properties;
    private final KlineHistoryRecorder klineHistoryRecorder;
    private final Object industryBoardCacheLock = new Object();
    private final Object eastMoneyRequestLock = new Object();
    private final Map<String, CachedQuotes> industryConstituentCache = new ConcurrentHashMap<>();
    private volatile CachedIndustryBoards cachedIndustryBoards = new CachedIndustryBoards(List.of(), Instant.EPOCH);
    private volatile long lastEastMoneyRequestMillis;

    public EastMoneyClient(RestClient restClient, ObjectMapper objectMapper, LiveDataProperties properties) {
        this(restClient, objectMapper, properties, null);
    }

    @Autowired
    public EastMoneyClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            LiveDataProperties properties,
            KlineHistoryRecorder klineHistoryRecorder
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.klineHistoryRecorder = klineHistoryRecorder;
    }

    public List<EastMoneyQuote> fetchLiquidAshareQuotes(int limit) {
        int safeLimit = Math.max(1, limit);
        int pageSize = Math.min(500, Math.max(50, safeLimit));
        RuntimeException[] failure = new RuntimeException[1];
        List<EastMoneyQuote> quotes = AshareQuotePaginator.collect(safeLimit, pageNumber -> {
            try {
                return fetchAshareQuotePage(pageNumber, pageSize);
            } catch (RuntimeException exception) {
                failure[0] = exception;
                return new AshareQuotePage(0, List.of());
            }
        });
        if (quotes.isEmpty() && failure[0] != null) {
            throw new IllegalStateException("东方财富行情数据获取失败", failure[0]);
        }
        return quotes;
    }

    public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
        return fetchAshareQuoteSnapshot(limit).quotes();
    }

    public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
        int requestedCount = Math.max(1, limit);
        int pageSize = Math.min(500, Math.max(50, requestedCount));
        Map<String, EastMoneyQuote> merged = new LinkedHashMap<>();
        RuntimeException pageFailure = null;
        int reportedTotal = 0;
        int expectedCount = requestedCount;
        int maxPages = Math.min(160, (int) Math.ceil(requestedCount / (double) pageSize));

        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            AshareQuotePage page;
            try {
                page = fetchAshareQuotePage(pageNumber, pageSize);
            } catch (RuntimeException exception) {
                pageFailure = exception;
                break;
            }
            if (page == null || page.quotes().isEmpty()) {
                break;
            }
            if (page.totalCount() > 0) {
                reportedTotal = page.totalCount();
                expectedCount = Math.min(requestedCount, reportedTotal);
                maxPages = Math.min(160, Math.max(1, (int) Math.ceil(expectedCount / (double) pageSize)));
            }
            page.quotes().stream()
                    .filter(quote -> quote.symbol() != null && !quote.symbol().isBlank())
                    .forEach(quote -> merged.putIfAbsent(quote.symbol(), quote));
            if (merged.size() >= expectedCount) {
                break;
            }
        }

        if (reportedTotal > 0) {
            expectedCount = Math.min(requestedCount, reportedTotal);
        }
        List<EastMoneyQuote> quotes = merged.values().stream().limit(expectedCount).toList();
        if (quotes.isEmpty()) {
            if (pageFailure != null) {
                throw new IllegalStateException("A股全市场实时行情获取失败：" + rootMessage(pageFailure), pageFailure);
            }
            throw new IllegalStateException("A股全市场实时行情返回空数据");
        }

        int fetchedCount = quotes.size();
        int missingCount = Math.max(0, expectedCount - fetchedCount);
        String source = quotes.stream()
                .map(EastMoneyQuote::sourceName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(" + "));
        return new AshareQuoteSnapshot(
                quotes,
                requestedCount,
                expectedCount,
                fetchedCount,
                missingCount,
                missingCount == 0,
                source.isBlank() ? "行情源未标注" : source,
                quotes.stream()
                        .map(EastMoneyQuote::fetchedAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Instant::compareTo)
                        .orElseGet(Instant::now)
        );
    }

    public List<EastMoneyQuote> fetchAshareQuotes() {
        return fetchAshareQuotes(500);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    public List<EastMoneyQuote> fetchAshareQuotesByPage(int pageNumber, int pageSize) {
        return fetchAshareQuotePage(pageNumber, pageSize).quotes();
    }

    AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
        int safePageNumber = Math.max(1, pageNumber);
        int safePageSize = Math.max(1, Math.min(500, pageSize));
        String url = properties.eastmoneyQuoteUrl()
                + "?pn=" + safePageNumber
                + "&pz=" + safePageSize
                + "&po=1"
                + "&fid=f6"
                + "&fs=" + A_SHARE_FILTER
                + "&fields=" + QUOTE_FIELDS;
        try {
            JsonNode data = fetchQuoteRoot(url).path("data");
            JsonNode diff = data.path("diff");
            List<EastMoneyQuote> quotes = new ArrayList<>();
            Instant fetchedAt = Instant.now();
            for (JsonNode item : diffItems(diff)) {
                readQuote(item, fetchedAt).ifPresent(quotes::add);
            }
            return new AshareQuotePage(data.path("total").asInt(0), quotes);
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富行情数据获取失败", exception);
        }
    }

    public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
        List<String> tencentSymbols = symbols.stream()
                .map(this::tencentCode)
                .filter(code -> code != null)
                .distinct()
                .toList();
        List<EastMoneyQuote> quotes = new ArrayList<>();
        Instant fetchedAt = Instant.now();
        Exception lastException = null;
        int attempts = 1;
        for (int start = 0; start < tencentSymbols.size(); start += 60) {
            int end = Math.min(start + 60, tencentSymbols.size());
            String url = "https://qt.gtimg.cn/q=" + String.join(",", tencentSymbols.subList(start, end));
            try {
                String body = fetchTencentBody(url, attempts);
                for (String line : body.split(";")) {
                    readTencentQuote(line, fetchedAt).ifPresent(quotes::add);
                }
            } catch (Exception exception) {
                lastException = exception;
            }
        }
        if (quotes.isEmpty() && lastException != null) {
            throw new IllegalStateException("腾讯行情数据获取失败", lastException);
        }
        return quotes.stream()
                .sorted(java.util.Comparator.comparing(EastMoneyQuote::amount, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    public List<EastMoneyQuote> fetchEastMoneyQuotesBySymbols(List<String> symbols, int limit) {
        List<String> secIds = symbols.stream()
                .map(this::secId)
                .filter(code -> code != null)
                .distinct()
                .toList();
        List<EastMoneyQuote> quotes = new ArrayList<>();
        Instant fetchedAt = Instant.now();
        Exception lastException = null;
        for (int start = 0; start < secIds.size(); start += 80) {
            int end = Math.min(start + 80, secIds.size());
            String url = "https://push2.eastmoney.com/api/qt/ulist.np/get"
                    + "?secids=" + String.join(",", secIds.subList(start, end))
                    + "&fields=" + QUOTE_FIELDS;
            try {
                JsonNode diff = fetchQuoteRoot(url).path("data").path("diff");
                if (diff.isObject()) {
                    Iterator<JsonNode> elements = diff.elements();
                    while (elements.hasNext()) {
                        readQuote(elements.next(), fetchedAt).ifPresent(quotes::add);
                    }
                } else if (diff.isArray()) {
                    for (JsonNode item : diff) {
                        readQuote(item, fetchedAt).ifPresent(quotes::add);
                    }
                }
            } catch (Exception exception) {
                lastException = exception;
            }
            if (quotes.size() >= limit) {
                return quotes.stream().limit(limit).toList();
            }
        }
        if (quotes.isEmpty() && lastException != null) {
            throw new IllegalStateException("东方财富批量行情数据获取失败", lastException);
        }
        return quotes.stream().limit(limit).toList();
    }

    public List<EastMoneyIntradayPoint> fetchIntradayTrends(String symbol) {
        RuntimeException primaryFailure = null;
        try {
            List<EastMoneyIntradayPoint> points = fetchTencentIntradayTrends(symbol);
            if (!points.isEmpty()) {
                return points;
            }
        } catch (RuntimeException exception) {
            primaryFailure = exception;
        }

        try {
            List<EastMoneyIntradayPoint> points = fetchEastMoneyIntradayTrends(symbol);
            if (!points.isEmpty()) {
                return points;
            }
        } catch (RuntimeException exception) {
            if (primaryFailure != null) {
                exception.addSuppressed(primaryFailure);
            }
            throw new IllegalStateException("当天分时数据获取失败：" + symbol, exception);
        }

        if (primaryFailure != null) {
            throw primaryFailure;
        }
        return List.of();
    }

    private List<EastMoneyIntradayPoint> fetchEastMoneyIntradayTrends(String symbol) {
        String secId = secId(symbol);
        if (secId == null) {
            return List.of();
        }
        String url = "https://push2his.eastmoney.com/api/qt/stock/trends2/get"
                + "?secid=" + encodeQueryValue(secId)
                + "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13"
                + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
                + "&iscr=0"
                + "&iscca=0"
                + "&ndays=1";
        try {
            String body = new String(fetchBytesWithCurl(url, null, "https://quote.eastmoney.com/", 3, 5, 7, 0), StandardCharsets.UTF_8);
            JsonNode trends = objectMapper.readTree(body)
                    .path("data")
                    .path("trends");
            if (!trends.isArray() || trends.isEmpty()) {
                return List.of();
            }
            List<EastMoneyIntradayPoint> points = new ArrayList<>();
            for (JsonNode item : trends) {
                readIntradayPoint(symbol, item.asText()).ifPresent(points::add);
            }
            return points;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富当天分时数据获取失败：" + symbol, exception);
        }
    }

    private List<EastMoneyIntradayPoint> fetchTencentIntradayTrends(String symbol) {
        String code = tencentCode(symbol);
        if (code == null) {
            return List.of();
        }
        String url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=" + code;
        try {
            String body = new String(fetchBytesWithCurl(url, null, "https://gu.qq.com/", 3, 6, 8, 0), StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(body)
                    .path("data")
                    .path(code)
                    .path("data");
            JsonNode rows = data.path("data");
            if (!rows.isArray() || rows.isEmpty()) {
                return List.of();
            }
            return readTencentIntradayPoints(symbol, data.path("date").asText(null), rows);
        } catch (Exception exception) {
            throw new IllegalStateException("腾讯当天分时数据获取失败：" + symbol, exception);
        }
    }

    public Optional<EastMoneyFundFlowSnapshot> fetchFundFlowSnapshot(String symbol) {
        String secId = secId(symbol);
        if (secId == null) {
            return Optional.empty();
        }
        String url = configured(
                properties == null ? null : properties.eastmoneyFundFlowUrl(),
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_URL
        )
                + "?fltt=2"
                + "&invt=2"
                + "&secids=" + encodeQueryValue(secId)
                + "&fields=" + FUND_FLOW_FIELDS;
        try {
            JsonNode diff = fetchQuoteRoot(url).path("data").path("diff");
            Instant fetchedAt = Instant.now();
            for (JsonNode item : diffItems(diff)) {
                Optional<EastMoneyFundFlowSnapshot> snapshot = readFundFlowSnapshot(item, symbol, fetchedAt, quoteUrl(symbol));
                if (snapshot.isPresent() && hasFundFlowValues(snapshot.get())) {
                    return snapshot;
                }
            }
            return fetchLatestFundFlowDaySnapshot(symbol, secId, fetchedAt);
        } catch (Exception exception) {
            try {
                return fetchLatestFundFlowDaySnapshot(symbol, secId, Instant.now());
            } catch (Exception fallbackException) {
                fallbackException.addSuppressed(exception);
                throw new IllegalStateException("东方财富资金流数据获取失败：" + symbol, fallbackException);
            }
        }
    }

    public List<EastMoneyFundFlowPoint> fetchFundFlowMinutes(String symbol, int limit) {
        String secId = secId(symbol);
        if (secId == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 240));
        String url = configured(
                properties == null ? null : properties.eastmoneyFundFlowMinuteUrl(),
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_MINUTE_URL
        )
                + "?ut=b2884a393a59ad64002292a3e90d46a5"
                + "&secid=" + encodeQueryValue(secId)
                + "&klt=1"
                + "&lmt=" + safeLimit
                + "&fields1=f1,f2,f3,f7"
                + "&fields2=" + FUND_FLOW_MINUTE_FIELDS;
        try {
            JsonNode klines = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return List.of();
            }
            List<EastMoneyFundFlowPoint> points = new ArrayList<>();
            for (JsonNode item : klines) {
                readFundFlowPoint(symbol, item.asText()).ifPresent(points::add);
            }
            return points;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富分钟资金流数据获取失败：" + symbol, exception);
        }
    }

    private Optional<EastMoneyFundFlowSnapshot> fetchLatestFundFlowDaySnapshot(String symbol, String secId, Instant fetchedAt) {
        String url = EASTMONEY_FUND_FLOW_DAY_URL
                + "?secid=" + encodeQueryValue(secId)
                + "&lmt=1"
                + "&fields1=f1,f2,f3,f7"
                + "&fields2=" + FUND_FLOW_DAY_FIELDS;
        try {
            JsonNode klines = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return Optional.empty();
            }
            return readFundFlowDaySnapshot(symbol, klines.get(klines.size() - 1).asText(), fetchedAt, quoteUrl(symbol));
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富日级资金流数据获取失败：" + symbol, exception);
        }
    }

    public String fetchCompanySurveyIndustry(String symbol) {
        String code = f10Code(symbol);
        if (code == null) {
            return null;
        }
        String url = "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax?code=" + code;
        try {
            JsonNode first = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://emweb.securities.eastmoney.com/"))
                    .path("jbzl")
                    .path(0);
            String emIndustry = text(first, "EM2016");
            if (emIndustry != null) {
                String[] parts = emIndustry.split("-");
                return normalize(parts.length >= 2 ? parts[1] : parts[parts.length - 1]);
            }
            String csrcIndustry = text(first, "INDUSTRYCSRC1");
            if (csrcIndustry != null) {
                String[] parts = csrcIndustry.split("-");
                return normalize(parts[parts.length - 1]);
            }
            return null;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富 F10 行业数据获取失败：" + symbol, exception);
        }
    }

    public String fetchStockBoardIndustry(String symbol) {
        String secId = secId(symbol);
        if (secId == null) {
            return null;
        }
        String url = "https://91.push2.eastmoney.com/api/qt/stock/get"
                + "?secid=" + secId
                + "&fields=f57,f58,f127";
        try {
            JsonNode data = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://quote.eastmoney.com/"))
                    .path("data");
            return normalize(text(data, "f127"));
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富单票板块行业数据获取失败：" + symbol, exception);
        }
    }

    public List<EastMoneyIndustryBoard> fetchIndustryBoards() {
        Instant now = Instant.now();
        CachedIndustryBoards cached = cachedIndustryBoards;
        if (now.isBefore(cached.expiresAt()) && !cached.boards().isEmpty()) {
            return cached.boards();
        }
        synchronized (industryBoardCacheLock) {
            cached = cachedIndustryBoards;
            if (now.isBefore(cached.expiresAt()) && !cached.boards().isEmpty()) {
                return cached.boards();
            }
            List<EastMoneyIndustryBoard> boards = fetchIndustryBoardsUncached();
            cachedIndustryBoards = new CachedIndustryBoards(boards, now.plusSeconds(INDUSTRY_BOARD_CACHE_SECONDS));
            return boards;
        }
    }

    private List<EastMoneyIndustryBoard> fetchIndustryBoardsUncached() {
        int pageSize = 100;
        int maxPages = 8;
        List<EastMoneyIndustryBoard> boards = new ArrayList<>();
        Exception lastException = null;
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            String url = "https://17.push2.eastmoney.com/api/qt/clist/get"
                    + "?pn=" + pageNumber
                    + "&pz=" + pageSize
                    + "&po=1"
                    + "&np=1"
                    + "&ut=bd1d9ddb04089700cf9c27f6f7426281"
                    + "&fltt=2"
                    + "&invt=2"
                    + "&fid=f3"
                    + "&fs=" + encodeQueryValue(INDUSTRY_BOARD_FILTER)
                    + "&fields=" + INDUSTRY_BOARD_FIELDS;
            try {
                JsonNode root = fetchQuoteRoot(url);
                JsonNode diff = root.path("data").path("diff");
                if (!diff.isArray() || diff.isEmpty()) {
                    break;
                }
                for (JsonNode item : diff) {
                    readIndustryBoard(item).ifPresent(boards::add);
                }
                int total = root.path("data").path("total").asInt(boards.size());
                if (boards.size() >= total) {
                    break;
                }
            } catch (Exception exception) {
                lastException = exception;
                if (!boards.isEmpty()) {
                    break;
                }
            }
        }
        if (boards.isEmpty() && lastException != null) {
            throw new IllegalStateException("东方财富行业板块列表获取失败", lastException);
        }
        return boards.stream().distinct().toList();
    }

    public List<EastMoneyQuote> fetchIndustryBoardConstituents(String industryName, int limit) {
        String boardCode = resolveIndustryBoardCode(industryName);
        if (boardCode == null) {
            return List.of();
        }
        String cacheKey = boardCode + ":" + limit;
        Instant now = Instant.now();
        CachedQuotes cached = industryConstituentCache.get(cacheKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.quotes();
        }
        int pageSize = Math.min(Math.max(limit, 50), 500);
        String url = "https://29.push2.eastmoney.com/api/qt/clist/get"
                + "?pn=1"
                + "&pz=" + pageSize
                + "&po=1"
                + "&np=1"
                + "&ut=bd1d9ddb04089700cf9c27f6f7426281"
                + "&invt=2"
                + "&fid=f6"
                + "&fs=" + encodeQueryValue("b:" + boardCode + " f:!50")
                + "&fields=" + QUOTE_FIELDS;
        try {
            JsonNode diff = fetchQuoteRoot(url).path("data").path("diff");
            List<EastMoneyQuote> quotes = new ArrayList<>();
            Instant fetchedAt = Instant.now();
            if (diff.isObject()) {
                Iterator<JsonNode> elements = diff.elements();
                while (elements.hasNext()) {
                    readQuote(elements.next(), fetchedAt).ifPresent(quotes::add);
                }
            } else if (diff.isArray()) {
                for (JsonNode item : diff) {
                    readQuote(item, fetchedAt).ifPresent(quotes::add);
                }
            }
            List<EastMoneyQuote> result = quotes.stream().limit(limit).toList();
            industryConstituentCache.put(cacheKey, new CachedQuotes(result, now.plusSeconds(INDUSTRY_CONSTITUENT_CACHE_SECONDS)));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富行业成分股获取失败：" + industryName, exception);
        }
    }

    public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
        EastMoneyKLineSeries series = fetchDailyKLineSeries(symbol, begin, end);
        if (!series.complete()) {
            throw new IllegalStateException("历史 K 线数据获取不完整：" + symbol + "，" + series.detail());
        }
        return series.rows();
    }

    public EastMoneyKLineSeries fetchDailyKLineSeries(String symbol, LocalDate begin, LocalDate end) {
        TencentKLineFetch tencent = null;
        Exception tencentFailure = null;
        try {
            tencent = fetchTencentDailyKLines(symbol, begin, end);
            if (tencent.complete() && !tencent.rows().isEmpty()) {
                recordKlineHistory(tencent.rows(), "腾讯前复权日线");
                return new EastMoneyKLineSeries(tencent.rows(), "TENCENT_QFQ_DAILY", true, null);
            }
        } catch (Exception exception) {
            tencentFailure = exception;
        }

        Exception eastMoneyFailure = null;
        try {
            List<EastMoneyKLine> eastMoneyKLines = fetchEastMoneyDailyKLines(symbol, begin, end);
            if (!eastMoneyKLines.isEmpty()) {
                recordKlineHistory(eastMoneyKLines, "东方财富前复权日线");
                return new EastMoneyKLineSeries(eastMoneyKLines, "EAST_MONEY_QFQ_DAILY", true, null);
            }
            if (tencent != null && tencent.complete()) {
                return new EastMoneyKLineSeries(
                        List.of(), "TENCENT_QFQ_DAILY+EAST_MONEY_QFQ_DAILY", true,
                        "两个历史行情源均返回空数据");
            }
        } catch (Exception exception) {
            eastMoneyFailure = exception;
        }

        if (tencent != null && !tencent.complete()) {
            String detail = "腾讯历史行情分片仅完成 " + tencent.successfulSlices() + "/"
                    + tencent.requestedSlices() + "，拒绝使用部分数据";
            if (eastMoneyFailure != null) {
                detail += "；东方财富回退失败：" + rootMessage(eastMoneyFailure);
            }
            return new EastMoneyKLineSeries(tencent.rows(), "TENCENT_QFQ_DAILY_PARTIAL", false, detail);
        }

        Throwable failure = eastMoneyFailure != null ? eastMoneyFailure : tencentFailure;
        String detail = failure == null ? "历史行情源未返回可用数据" : rootMessage(failure);
        return new EastMoneyKLineSeries(List.of(), "HISTORICAL_KLINE_UNAVAILABLE", false, detail);
    }

    private void recordKlineHistory(List<EastMoneyKLine> rows, String sourceName) {
        if (klineHistoryRecorder != null) {
            try {
                klineHistoryRecorder.record(rows, sourceName);
            } catch (RuntimeException exception) {
                logger.warn("K 线历史归档失败，不中断实时数据返回：{}", exception.getMessage());
            }
        }
    }

    private List<EastMoneyKLine> fetchEastMoneyDailyKLines(String symbol, LocalDate begin, LocalDate end) {
        String secId = secId(symbol);
        if (secId == null) {
            return List.of();
        }
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                + "?secid=" + secId
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=f51,f52,f53,f54,f55,f56,f57"
                + "&klt=101"
                + "&fqt=1"
                + "&beg=" + dateParam(begin)
                + "&end=" + dateParam(end);
        try {
            JsonNode klines = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return List.of();
            }
            List<EastMoneyKLine> items = new ArrayList<>();
            for (JsonNode item : klines) {
                readKLine(symbol, item.asText()).ifPresent(items::add);
            }
            return items;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富历史 K 线数据获取失败：" + symbol, exception);
        }
    }

    private String resolveIndustryBoardCode(String industryName) {
        String normalizedIndustry = normalizeIndustry(industryName);
        if (normalizedIndustry == null) {
            return null;
        }
        if (normalizedIndustry.matches("BK\\d+")) {
            return normalizedIndustry;
        }
        return fetchIndustryBoards().stream()
                .map(board -> new IndustryMatch(board, industryMatchScore(normalizedIndustry, board.name())))
                .filter(match -> match.score() > 0)
                .sorted(java.util.Comparator.comparing(IndustryMatch::score).reversed()
                        .thenComparing(match -> match.board().name().length()))
                .map(match -> match.board().code())
                .findFirst()
                .orElse(null);
    }

    private int industryMatchScore(String normalizedIndustry, String boardName) {
        String normalizedBoard = normalizeIndustry(boardName);
        if (normalizedBoard == null) {
            return 0;
        }
        if (normalizedIndustry.equals(normalizedBoard)) {
            return 100;
        }
        String compactIndustry = compactIndustryName(normalizedIndustry);
        String compactBoard = compactIndustryName(normalizedBoard);
        if (compactIndustry.equals(compactBoard)) {
            return 95;
        }
        if (compactBoard.contains(compactIndustry) || compactIndustry.contains(compactBoard)) {
            return 70 - Math.abs(compactBoard.length() - compactIndustry.length());
        }
        return 0;
    }

    private String compactIndustryName(String value) {
        return value.replace("行业", "")
                .replace("板块", "")
                .replace("Ⅱ", "")
                .replace("Ⅲ", "")
                .replace("II", "")
                .replace("III", "")
                .trim();
    }

    private Optional<EastMoneyIndustryBoard> readIndustryBoard(JsonNode item) {
        String code = text(item, "f12");
        String name = text(item, "f14");
        if (code == null || name == null || !code.startsWith("BK")) {
            return Optional.empty();
        }
        return Optional.of(new EastMoneyIndustryBoard(code, name));
    }

    private TencentKLineFetch fetchTencentDailyKLines(String symbol, LocalDate begin, LocalDate end) {
        String code = tencentCode(symbol);
        if (code == null) {
            return new TencentKLineFetch(List.of(), 0, 0, true);
        }
        Map<LocalDate, EastMoneyKLine> byDate = new HashMap<>();
        int requestedSlices = 0;
        int successfulSlices = 0;
        LocalDate cursor = begin;
        while (!cursor.isAfter(end)) {
            requestedSlices++;
            LocalDate sliceEnd = cursor.plusYears(1).minusDays(1);
            if (sliceEnd.isAfter(end)) {
                sliceEnd = end;
            }
            String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
                    + "?param=" + code
                    + ",day,"
                    + cursor
                    + ","
                    + sliceEnd
                    + ",500,qfq";
            try {
                JsonNode rows = objectMapper.readTree(fetchBodyWithCurl(url, null, "https://gu.qq.com/"))
                        .path("data")
                        .path(code);
                JsonNode dailyRows = rows.path("qfqday");
                if (!dailyRows.isArray() || dailyRows.isEmpty()) {
                    dailyRows = rows.path("day");
                }
                if (dailyRows.isArray()) {
                    for (JsonNode row : dailyRows) {
                        readTencentKLine(symbol, row).ifPresent(kline -> byDate.put(kline.tradeDate(), kline));
                    }
                }
                successfulSlices++;
            } catch (Exception exception) {
                logger.warn("腾讯历史 K 线分片获取失败：{} {}~{}，原因：{}",
                        symbol, cursor, sliceEnd, rootMessage(exception));
            }
            cursor = sliceEnd.plusDays(1);
        }
        return new TencentKLineFetch(
                byDate.values().stream()
                        .sorted(java.util.Comparator.comparing(EastMoneyKLine::tradeDate))
                        .toList(),
                requestedSlices,
                successfulSlices,
                requestedSlices == successfulSlices);
    }

    private record TencentKLineFetch(
            List<EastMoneyKLine> rows,
            int requestedSlices,
            int successfulSlices,
            boolean complete
    ) {
    }

    private JsonNode fetchQuoteRoot(String url) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= EASTMONEY_ATTEMPTS; attempt++) {
            try {
                String body = fetchBodyWithCurl(url, lastException, "https://quote.eastmoney.com/");
                String trimmedBody = body == null ? "" : body.trim();
                if (!trimmedBody.startsWith("{")) {
                    lastException = new IllegalStateException("东方财富行情返回非 JSON 内容：" + sampleBody(trimmedBody));
                    continue;
                }
                JsonNode root = objectMapper.readTree(trimmedBody);
                JsonNode diff = root.path("data").path("diff");
                if (root.path("rc").asInt(-1) == 0 && (diff.isArray() || diff.isObject())) {
                    return root;
                }
                lastException = new IllegalStateException("东方财富行情 JSON 结构异常：" + sampleBody(trimmedBody));
            } catch (Exception exception) {
                lastException = exception;
            }
            quietSleep(250L * attempt);
        }
        throw new IllegalStateException("东方财富行情数据获取失败", lastException);
    }

    private String fetchBody(String url) {
        List<String> candidateUrls = remoteCandidateUrls(url);
        Exception restClientException = null;
        for (String candidateUrl : candidateUrls) {
            throttleEastMoney(candidateUrl);
            try {
                return restClient.get().uri(URI.create(candidateUrl)).retrieve().body(String.class);
            } catch (Exception exception) {
                if (restClientException == null) {
                    restClientException = exception;
                } else {
                    restClientException.addSuppressed(exception);
                }
            }
        }
        return fetchBodyWithCurl(url, restClientException, null);
    }

    private String fetchTencentBody(String url) {
        return fetchTencentBody(url, 1);
    }

    private String fetchTencentBody(String url, int attempts) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            try {
                byte[] body = fetchBytesWithCurl(url, lastException, "https://gu.qq.com/", 4, 8, 10, 0);
                String decodedBody = new String(body, GB18030);
                if (decodedBody.contains("=\"")) {
                    return decodedBody;
                }
                lastException = new IllegalStateException("腾讯行情返回结构异常：" + sampleBody(decodedBody));
            } catch (Exception exception) {
                lastException = exception;
            }
        }
        throw new IllegalStateException("腾讯行情数据获取失败", lastException);
    }

    private String fetchBodyWithCurl(String url, Exception restClientException, String referer) {
        return new String(fetchBytesWithCurl(url, restClientException, referer), StandardCharsets.UTF_8);
    }

    private byte[] fetchBytesWithCurl(String url, Exception restClientException, String referer) {
        return fetchBytesWithCurl(url, restClientException, referer, 5, 9, 11, 0);
    }

    private byte[] fetchBytesWithCurl(
            String url,
            Exception restClientException,
            String referer,
            int connectTimeoutSeconds,
            int maxTimeSeconds,
            int processWaitSeconds,
            int retryCount
    ) {
        List<String> candidateUrls = remoteCandidateUrls(url);
        List<Exception> failures = new ArrayList<>();
        int attempts = remoteAttempts(url);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            for (String candidateUrl : rotate(candidateUrls, attempt - 1)) {
                try {
                    return fetchBytesOnceWithCurl(
                            candidateUrl,
                            restClientException,
                            referer,
                            connectTimeoutSeconds,
                            maxTimeSeconds,
                            processWaitSeconds,
                            retryCount
                    );
                } catch (Exception exception) {
                    failures.add(exception);
                }
                quietSleep(180L * attempt);
            }
        }
        IllegalStateException aggregate = new IllegalStateException(
                "远程数据获取失败，已轮询 " + candidateUrls.size()
                        + " 个地址、尝试 " + attempts
                        + " 轮：" + failureSummary(failures),
                failures.isEmpty() ? restClientException : failures.get(failures.size() - 1)
        );
        if (restClientException != null) {
            aggregate.addSuppressed(restClientException);
        }
        failures.forEach(aggregate::addSuppressed);
        throw aggregate;
    }

    private byte[] fetchBytesOnceWithCurl(
            String url,
            Exception restClientException,
            String referer,
            int connectTimeoutSeconds,
            int maxTimeSeconds,
            int processWaitSeconds,
            int retryCount
    ) {
        try {
            throttleEastMoney(url);
            List<String> command = new ArrayList<>(List.of(
                    "curl",
                    "-L",
                    "--compressed",
                    "-sS",
                    "--ipv4",
                    "--http1.1",
                    "--retry",
                    String.valueOf(Math.max(0, retryCount)),
                    "--retry-delay",
                    "1",
                    "--retry-all-errors",
                    "--connect-timeout",
                    String.valueOf(Math.max(1, connectTimeoutSeconds)),
                    "--max-time",
                    String.valueOf(Math.max(1, maxTimeSeconds)),
                    "-H",
                    "User-Agent: Mozilla/5.0 AI-Stock-Research/0.1",
                    "-H",
                    "Accept: application/json,text/plain,*/*"
            ));
            if (referer != null) {
                command.add("-H");
                command.add("Referer: " + referer);
            }
            command.add(url);
            Process process = new ProcessBuilder(command).start();
            boolean completed = process.waitFor(Math.max(2, processWaitSeconds), TimeUnit.SECONDS);
            byte[] stdout = process.getInputStream().readAllBytes();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("curl 请求超时：" + hostLabel(url), restClientException);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("curl 请求失败：" + hostLabel(url) + "，" + stderr, restClientException);
            }
            return stdout;
        } catch (Exception curlException) {
            if (restClientException != null) {
                curlException.addSuppressed(restClientException);
            }
            throw new IllegalStateException("curl 执行失败：" + hostLabel(url), curlException);
        }
    }

    List<String> remoteCandidateUrls(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        String host = host(url);
        if (host == null) {
            return List.of(url);
        }
        if (isPush2Host(host)) {
            for (String fallbackHost : EASTMONEY_PUSH2_HOSTS) {
                if (fallbackHost.equals(host)) {
                    urls.putIfAbsent(url, url);
                } else {
                    replaceHost(url, fallbackHost).ifPresent(candidate -> urls.putIfAbsent(candidate, candidate));
                }
            }
            urls.putIfAbsent(url, url);
            return new ArrayList<>(urls.values());
        }
        urls.put(url, url);
        return new ArrayList<>(urls.values());
    }

    private List<String> rotate(List<String> values, int offset) {
        if (values.isEmpty()) {
            return values;
        }
        int safeOffset = Math.floorMod(offset, values.size());
        List<String> rotated = new ArrayList<>(values.size());
        rotated.addAll(values.subList(safeOffset, values.size()));
        rotated.addAll(values.subList(0, safeOffset));
        return rotated;
    }

    private int remoteAttempts(String url) {
        if (url == null) {
            return 1;
        }
        if (url.contains("eastmoney.com")) {
            return EASTMONEY_REMOTE_ATTEMPTS;
        }
        if (url.contains("cninfo.com.cn")) {
            return GENERIC_REMOTE_ATTEMPTS;
        }
        return 1;
    }

    private boolean isPush2Host(String host) {
        return "push2.eastmoney.com".equals(host)
                || "push2delay.eastmoney.com".equals(host)
                || host.matches("\\d+\\.push2\\.eastmoney\\.com");
    }

    private Optional<String> replaceHost(String url, String newHost) {
        String currentHost = host(url);
        if (currentHost == null || currentHost.equals(newHost)) {
            return Optional.empty();
        }
        String marker = "://" + currentHost;
        int index = url.indexOf(marker);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(url.substring(0, index + 3) + newHost + url.substring(index + marker.length()));
    }

    private String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String hostLabel(String url) {
        String host = host(url);
        return host == null ? "unknown-host" : host;
    }

    private String failureSummary(List<Exception> failures) {
        if (failures.isEmpty()) {
            return "无详细错误";
        }
        return failures.stream()
                .map(this::rootMessage)
                .distinct()
                .limit(3)
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private void throttleEastMoney(String url) {
        if (url == null || !url.contains("eastmoney.com")) {
            return;
        }
        synchronized (eastMoneyRequestLock) {
            long now = System.currentTimeMillis();
            long waitMillis = EASTMONEY_MIN_INTERVAL_MILLIS - (now - lastEastMoneyRequestMillis);
            if (waitMillis > 0) {
                quietSleep(waitMillis);
            }
            lastEastMoneyRequestMillis = System.currentTimeMillis();
        }
    }

    private void quietSleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, EastMoneyAnnualIndicator> fetchAnnualIndicators(int dataYear, int pageSize) {
        String dataType = dataYear + "年 年报";
        String filter = "(DATAYEAR=\"" + dataYear + "\")(DATATYPE=\"" + dataType + "\")";
        int requestPageSize = Math.min(Math.max(pageSize, 1), 500);
        int maxPages = Math.max(1, (int) Math.ceil(pageSize / (double) requestPageSize));
        try {
            Map<String, EastMoneyAnnualIndicator> indicators = new HashMap<>();
            for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
                String url = properties.eastmoneyFinancialUrl()
                        + "?sortColumns=SECURITY_CODE"
                        + "&sortTypes=1"
                        + "&pageSize=" + requestPageSize
                        + "&pageNumber=" + pageNumber
                        + "&reportName=RPT_LICO_FN_CPD"
                        + "&columns=" + FINANCIAL_COLUMNS
                        + "&filter=" + encodeFilter(filter);
                String body = fetchBody(url);
                JsonNode result = objectMapper.readTree(body).path("result");
                JsonNode data = result.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    break;
                }
                for (JsonNode item : data) {
                    readAnnualIndicator(item).ifPresent(indicator -> indicators.put(indicator.symbol(), indicator));
                    if (indicators.size() >= pageSize) {
                        return indicators;
                    }
                }
                int pages = result.path("pages").asInt(maxPages);
                if (pageNumber >= pages) {
                    break;
                }
            }
            return indicators;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富年报指标数据获取失败", exception);
        }
    }

    public List<EastMoneyAnnualIndicator> fetchAnnualIndicatorHistory(String symbol, int limit) {
        int requestLimit = Math.min(Math.max(limit * 4, 12), 80);
        String filter = "(SECURITY_CODE=\"" + symbol + "\")";
        try {
            String url = properties.eastmoneyFinancialUrl()
                    + "?sortColumns=REPORTDATE"
                    + "&sortTypes=-1"
                    + "&pageSize=" + requestLimit
                    + "&pageNumber=1"
                    + "&reportName=RPT_LICO_FN_CPD"
                    + "&columns=" + FINANCIAL_COLUMNS
                    + "&filter=" + encodeFilter(filter);
            String body = fetchBody(url);
            JsonNode data = objectMapper.readTree(body).path("result").path("data");
            if (!data.isArray() || data.isEmpty()) {
                return List.of();
            }
            List<EastMoneyAnnualIndicator> history = new ArrayList<>();
            for (JsonNode item : data) {
                readAnnualIndicator(item)
                        .filter(indicator -> indicator.dataType() != null && indicator.dataType().contains("年报"))
                        .filter(indicator -> isYearEndReportDate(indicator.reportDate()))
                        .ifPresent(history::add);
                if (history.size() >= limit) {
                    return history;
                }
            }
            return history;
        } catch (Exception exception) {
            throw new IllegalStateException("东方财富历史年报指标数据获取失败：" + symbol, exception);
        }
    }

    private java.util.Optional<EastMoneyAnnualIndicator> readAnnualIndicator(JsonNode item) {
        String symbol = text(item, "SECURITY_CODE");
        if (symbol == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new EastMoneyAnnualIndicator(
                symbol,
                text(item, "SECURITY_NAME_ABBR"),
                text(item, "REPORTDATE"),
                text(item, "DATATYPE"),
                percent(item, "WEIGHTAVG_ROE"),
                decimal(item, "MGJYXJJE"),
                percent(item, "XSMLL"),
                percent(item, "YSTZ"),
                percent(item, "SJLTZ"),
                decimal(item, "BASIC_EPS"),
                decimal(item, "BPS")
        ));
    }

    java.util.Optional<EastMoneyQuote> readQuote(JsonNode item, Instant fetchedAt) {
        String symbol = text(item, "f12");
        String name = text(item, "f14");
        if (symbol == null || name == null) {
            return java.util.Optional.empty();
        }
        String market = marketName(item.path("f13").asInt(-1), symbol);
        Instant marketTimestamp = epochSecond(item, "f124");
        return java.util.Optional.of(new EastMoneyQuote(
                symbol,
                name,
                market,
                text(item, "f100"),
                positiveOrNull(scaled(item, "f2", 2)),
                scaled(item, "f3", 2),
                scaled(item, "f8", 2),
                decimal(item, "f5"),
                decimal(item, "f6"),
                scaled(item, "f9", 2),
                scaled(item, "f23", 2),
                scaled(item, "f115", 2),
                "东方财富行情",
                quoteUrl(symbol),
                fetchedAt,
                tradeDate(marketTimestamp),
                marketTimestamp
        ));
    }

    java.util.Optional<EastMoneyQuote> readTencentQuote(String line, Instant fetchedAt) {
        int start = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return java.util.Optional.empty();
        }
        String[] fields = line.substring(start + 1, end).split("~", -1);
        if (fields.length < 49) {
            return java.util.Optional.empty();
        }
        String symbol = normalize(fields[2]);
        String name = normalize(fields[1]);
        if (symbol == null || name == null) {
            return java.util.Optional.empty();
        }
        BigDecimal amount = decimal(fields[37]);
        if (amount != null) {
            amount = amount.multiply(BigDecimal.valueOf(10000));
        }
        Instant marketTimestamp = parseTencentQuoteTimestamp(fields[30]);
        return java.util.Optional.of(new EastMoneyQuote(
                symbol,
                name,
                marketName(symbol.startsWith("6") ? 1 : 0, symbol),
                null,
                decimal(fields[3]),
                decimal(fields[32]),
                decimal(fields[38]),
                decimal(fields[36]),
                amount,
                positiveOrNull(decimal(fields[39])),
                positiveOrNull(decimal(fields[46])),
                positiveOrNull(decimal(fields[39])),
                "腾讯行情",
                quoteUrl(symbol),
                fetchedAt,
                tradeDate(marketTimestamp),
                marketTimestamp
        ));
    }

    private Instant epochSecond(JsonNode item, String field) {
        long value = item.path(field).asLong(0L);
        return value <= 0L ? null : Instant.ofEpochSecond(value);
    }

    private Instant parseTencentQuoteTimestamp(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() != 14) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized, TENCENT_QUOTE_TIMESTAMP)
                    .atZone(CHINA_MARKET_ZONE)
                    .toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LocalDate tradeDate(Instant marketTimestamp) {
        return marketTimestamp == null ? null : marketTimestamp.atZone(CHINA_MARKET_ZONE).toLocalDate();
    }

    Optional<EastMoneyFundFlowSnapshot> readFundFlowSnapshot(JsonNode item, String requestedSymbol, Instant fetchedAt, String sourceUrl) {
        String symbol = firstText(text(item, "f12"), requestedSymbol);
        if (symbol == null) {
            return Optional.empty();
        }
        return Optional.of(new EastMoneyFundFlowSnapshot(
                symbol,
                text(item, "f14"),
                decimal(item, "f62"),
                decimal(item, "f66"),
                decimal(item, "f72"),
                decimal(item, "f78"),
                decimal(item, "f84"),
                decimal(item, "f184"),
                decimal(item, "f69"),
                decimal(item, "f75"),
                decimal(item, "f81"),
                decimal(item, "f87"),
                "东方财富资金流",
                sourceUrl,
                fetchedAt
        ));
    }

    Optional<EastMoneyFundFlowPoint> readFundFlowPoint(String symbol, String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return Optional.empty();
        }
        String[] fields = rawLine.split(",", -1);
        if (fields.length < 6) {
            return Optional.empty();
        }
        return Optional.of(new EastMoneyFundFlowPoint(
                symbol,
                normalize(fields[0]),
                decimal(fields[1]),
                decimal(fields[2]),
                decimal(fields[3]),
                decimal(fields[4]),
                decimal(fields[5])
        ));
    }

    Optional<EastMoneyFundFlowSnapshot> readFundFlowDaySnapshot(String symbol, String rawLine, Instant fetchedAt, String sourceUrl) {
        if (rawLine == null || rawLine.isBlank()) {
            return Optional.empty();
        }
        String[] fields = rawLine.split(",", -1);
        if (fields.length < 11) {
            return Optional.empty();
        }
        String tradeDate = normalize(fields[0]);
        return Optional.of(new EastMoneyFundFlowSnapshot(
                symbol,
                null,
                decimal(fields[1]),
                decimal(fields[5]),
                decimal(fields[4]),
                decimal(fields[3]),
                decimal(fields[2]),
                decimal(fields[6]),
                decimal(fields[10]),
                decimal(fields[9]),
                decimal(fields[8]),
                decimal(fields[7]),
                tradeDate == null ? "东方财富资金流" : "东方财富资金流（日级最近交易日 " + tradeDate + "）",
                sourceUrl,
                fetchedAt
        ));
    }

    private java.util.Optional<EastMoneyKLine> readKLine(String symbol, String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] fields = rawLine.split(",", -1);
        if (fields.length < 7) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new EastMoneyKLine(
                    symbol,
                    LocalDate.parse(fields[0]),
                    decimal(fields[1]),
                    decimal(fields[2]),
                    decimal(fields[3]),
                    decimal(fields[4]),
                    decimal(fields[5]),
                    decimal(fields[6])
            ));
        } catch (Exception exception) {
            return java.util.Optional.empty();
        }
    }

    java.util.Optional<EastMoneyIntradayPoint> readIntradayPoint(String symbol, String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] fields = rawLine.split(",");
        if (fields.length < 8) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new EastMoneyIntradayPoint(
                    symbol,
                    LocalDateTime.parse(fields[0].replace(" ", "T")),
                    decimal(fields[1]),
                    decimal(fields[2]),
                    decimal(fields[3]),
                    decimal(fields[4]),
                    decimal(fields[5]),
                    decimal(fields[6]),
                    decimal(fields[7])
            ));
        } catch (Exception exception) {
            return java.util.Optional.empty();
        }
    }

    List<EastMoneyIntradayPoint> readTencentIntradayPoints(String symbol, String dateText, JsonNode rows) {
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return List.of();
        }
        LocalDate tradeDate = parseTencentTradeDate(dateText);
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        BigDecimal previousVolume = BigDecimal.ZERO;
        BigDecimal previousAmount = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            String[] fields = row.asText("").trim().split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            try {
                LocalTime minute = LocalTime.of(
                        Integer.parseInt(fields[0].substring(0, 2)),
                        Integer.parseInt(fields[0].substring(2, 4))
                );
                BigDecimal price = decimal(fields[1]);
                BigDecimal cumulativeVolume = decimal(fields[2]);
                BigDecimal cumulativeAmount = decimal(fields[3]);
                if (price == null || cumulativeVolume == null || cumulativeAmount == null) {
                    continue;
                }
                BigDecimal minuteVolume = positiveDelta(cumulativeVolume, previousVolume);
                BigDecimal minuteAmount = positiveDelta(cumulativeAmount, previousAmount);
                BigDecimal averagePrice = tencentAveragePrice(price, cumulativeVolume, cumulativeAmount);
                points.add(new EastMoneyIntradayPoint(
                        symbol,
                        LocalDateTime.of(tradeDate, minute),
                        price,
                        price,
                        price,
                        price,
                        minuteVolume,
                        minuteAmount,
                        averagePrice
                ));
                previousVolume = cumulativeVolume;
                previousAmount = cumulativeAmount;
            } catch (Exception ignored) {
                // Ignore malformed minute rows from the remote feed.
            }
        }
        return points;
    }

    private BigDecimal tencentAveragePrice(BigDecimal price, BigDecimal cumulativeVolume, BigDecimal cumulativeAmount) {
        if (cumulativeVolume == null || cumulativeAmount == null || cumulativeVolume.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal averageByLots = cumulativeAmount
                .divide(cumulativeVolume.multiply(new BigDecimal("100")), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        BigDecimal averageByShares = cumulativeAmount
                .divide(cumulativeVolume, 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return averageByLots;
        }
        BigDecimal lotsDistance = averageByLots.subtract(price).abs();
        BigDecimal sharesDistance = averageByShares.subtract(price).abs();
        return sharesDistance.compareTo(lotsDistance) < 0 ? averageByShares : averageByLots;
    }

    private LocalDate parseTencentTradeDate(String dateText) {
        try {
            if (dateText != null && !dateText.isBlank()) {
                return LocalDate.parse(dateText, DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (Exception ignored) {
            // Fall through to today when the feed omits or mangles the date.
        }
        return LocalDate.now();
    }

    private BigDecimal positiveDelta(BigDecimal current, BigDecimal previous) {
        if (current == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal delta = current.subtract(previous == null ? BigDecimal.ZERO : previous);
        return delta.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : delta.stripTrailingZeros();
    }

    private java.util.Optional<EastMoneyKLine> readTencentKLine(String symbol, JsonNode row) {
        if (!row.isArray() || row.size() < 6) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new EastMoneyKLine(
                    symbol,
                    LocalDate.parse(row.get(0).asText()),
                    decimal(row.get(1).asText()),
                    decimal(row.get(2).asText()),
                    decimal(row.get(3).asText()),
                    decimal(row.get(4).asText()),
                    decimal(row.get(5).asText()),
                    null
            ));
        } catch (Exception exception) {
            return java.util.Optional.empty();
        }
    }

    private String marketName(int marketCode, String symbol) {
        if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92")) {
            return "北交所";
        }
        if (marketCode == 1 || symbol.startsWith("6")) {
            return "上交所";
        }
        return "深交所";
    }

    private String quoteUrl(String symbol) {
        if (symbol.startsWith("6")) {
            return "https://quote.eastmoney.com/sh" + symbol + ".html";
        }
        if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92")) {
            return "https://quote.eastmoney.com/bj" + symbol + ".html";
        }
        return "https://quote.eastmoney.com/sz" + symbol + ".html";
    }

    private String encodeFilter(String filter) {
        return URLEncoder.encode(filter, StandardCharsets.UTF_8);
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || "-".equals(value.asText())) {
            return null;
        }
        return value.asText();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || "-".equals(value.asText())) {
            return null;
        }
        return BigDecimal.valueOf(value.asDouble()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal percent(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        if (value == null) {
            return null;
        }
        return value.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal decimal(String rawValue) {
        String value = normalize(rawValue);
        if (value == null || "-".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value;
    }

    private BigDecimal firstPresent(BigDecimal primary, BigDecimal fallback) {
        if (primary != null && primary.compareTo(BigDecimal.ZERO) > 0) {
            return primary;
        }
        return fallback;
    }

    private String firstText(String primary, String fallback) {
        return primary == null ? fallback : primary;
    }

    private BigDecimal scaled(JsonNode node, String field, int scale) {
        BigDecimal value = decimal(node, field);
        if (value == null) {
            return null;
        }
        return value.movePointLeft(scale).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String tencentCode(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if (symbol.startsWith("6")) {
            return "sh" + symbol;
        }
        if (symbol.startsWith("0") || symbol.startsWith("3")) {
            return "sz" + symbol;
        }
        if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92")) {
            return "bj" + symbol;
        }
        return null;
    }

    private String secId(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if (symbol.startsWith("6")) {
            return "1." + symbol;
        }
        if (symbol.startsWith("0") || symbol.startsWith("3")) {
            return "0." + symbol;
        }
        if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92")) {
            return "0." + symbol;
        }
        return null;
    }

    private String f10Code(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if (symbol.startsWith("6")) {
            return "SH" + symbol;
        }
        if (symbol.startsWith("0") || symbol.startsWith("3")) {
            return "SZ" + symbol;
        }
        if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92")) {
            return "BJ" + symbol;
        }
        return null;
    }

    private String dateParam(LocalDate date) {
        return date.toString().replace("-", "");
    }

    private boolean isYearEndReportDate(String reportDate) {
        return reportDate != null && reportDate.contains("-12-31");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeIndustry(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private String configured(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private boolean hasFundFlowValues(EastMoneyFundFlowSnapshot snapshot) {
        return snapshot.mainNetInflow() != null
                || snapshot.superLargeNetInflow() != null
                || snapshot.largeNetInflow() != null
                || snapshot.mediumNetInflow() != null
                || snapshot.smallNetInflow() != null;
    }

    private List<JsonNode> diffItems(JsonNode diff) {
        List<JsonNode> items = new ArrayList<>();
        if (diff.isObject()) {
            Iterator<JsonNode> elements = diff.elements();
            while (elements.hasNext()) {
                items.add(elements.next());
            }
        } else if (diff.isArray()) {
            for (JsonNode item : diff) {
                items.add(item);
            }
        }
        return items;
    }

    private String sampleBody(String body) {
        if (body == null || body.isBlank()) {
            return "空响应";
        }
        String compactBody = body.replaceAll("\\s+", " ");
        if (compactBody.length() <= 160) {
            return compactBody;
        }
        return compactBody.substring(0, 160);
    }

    private record IndustryMatch(EastMoneyIndustryBoard board, int score) {
    }

    private record CachedIndustryBoards(List<EastMoneyIndustryBoard> boards, Instant expiresAt) {
    }

    private record CachedQuotes(List<EastMoneyQuote> quotes, Instant expiresAt) {
    }

}
