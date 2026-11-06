package com.aistock.research.integration.eastmoney;

import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SinaDailyKLineParser {
    private static final String SOURCE = "SINA_QFQ_DAILY";
    private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(120);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SinaDailyKLineParser() {
    }

    public static EastMoneyKLineSeries parse(String symbol, String dailyJson, String factorJs,
                                            LocalDate begin, LocalDate end,
                                            EastMoneyQuote currentQuote, LocalDateTime fetchedAt) {
        try {
            require(begin != null && end != null && fetchedAt != null && !begin.isAfter(end), "请求日期范围无效");
            LocalDate today = fetchedAt.toLocalDate();
            require(!end.isAfter(today), "请求包含未来日期");
            NavigableMap<LocalDate, BigDecimal> factors = factors(symbol, factorJs, today);
            JsonNode daily = json(dailyJson);
            require(daily.isArray() && !daily.isEmpty(), "历史日线为空或格式无效");
            NavigableMap<LocalDate, EastMoneyKLine> rows = new TreeMap<>();
            // Validate every source row before cropping or replacing today's bar.
            for (JsonNode node : daily) {
                LocalDate date = date(node, "day", today);
                BigDecimal open = number(node, "open");
                BigDecimal close = number(node, "close");
                BigDecimal high = number(node, "high");
                BigDecimal low = number(node, "low");
                BigDecimal volume = number(node, "volume");
                EastMoneyKLine raw = new EastMoneyKLine(symbol, date, open, close, high, low, volume, null);
                validateBar(raw);
                require(!rows.containsKey(date), "历史日线包含重复交易日 " + date);
                Map.Entry<LocalDate, BigDecimal> entry = factors.floorEntry(date);
                require(entry != null, "复权因子未覆盖交易日 " + date);
                BigDecimal factor = entry.getValue();
                rows.put(date, new EastMoneyKLine(symbol, date,
                        open.divide(factor, MathContext.DECIMAL128), close.divide(factor, MathContext.DECIMAL128),
                        high.divide(factor, MathContext.DECIMAL128), low.divide(factor, MathContext.DECIMAL128),
                        volume.movePointLeft(2), null, null));
            }
            if (end.equals(today)) {
                List<EastMoneyKLine> historicalRows = begin.isBefore(today)
                        ? List.copyOf(rows.subMap(begin, true, today.minusDays(1), true).values()) : List.of();
                if (begin.isBefore(today)) {
                    LocalDate historicalEnd = today.minusDays(1);
                    KLineSeriesIntegrity.Result history = KLineSeriesIntegrity.assess(
                            historicalRows,
                            daily.size(), daily.size(), 0, 1, 1, begin, historicalEnd);
                    if (!history.complete()) {
                        return new EastMoneyKLineSeries(historicalRows, SOURCE, false, history.detail());
                    }
                }
                try {
                    rows.put(today, currentBar(symbol, currentQuote, fetchedAt));
                } catch (IllegalArgumentException exception) {
                    return new EastMoneyKLineSeries(historicalRows, SOURCE, false,
                            "新浪前复权日线校验失败：" + exception.getMessage());
                }
            }
            List<EastMoneyKLine> selected = List.copyOf(rows.subMap(begin, true, end, true).values());
            KLineSeriesIntegrity.Result integrity = KLineSeriesIntegrity.assess(
                    selected, daily.size(), daily.size(), 0, 1, 1, begin, end);
            return new EastMoneyKLineSeries(selected, SOURCE, integrity.complete(), integrity.detail());
        } catch (JsonProcessingException | IllegalArgumentException | DateTimeException exception) {
            return new EastMoneyKLineSeries(List.of(), SOURCE, false,
                    "新浪前复权日线校验失败：" + exception.getMessage());
        }
    }

    private static NavigableMap<LocalDate, BigDecimal> factors(String symbol, String factorJs, LocalDate today)
            throws JsonProcessingException {
        String qualifiedSymbol = qualifiedSymbol(symbol);
        require(factorJs != null, "复权因子为空");
        Matcher assignment = Pattern.compile("\\s*var\\s+" + qualifiedSymbol
                + "qfq\\s*=\\s*(\\{.*})\\s*;?\\s*(?:/\\*(?:(?!\\*/).)*\\*/\\s*)*", Pattern.DOTALL).matcher(factorJs);
        require(assignment.matches(), "复权因子身份或赋值格式不匹配");
        JsonNode root = json(assignment.group(1));
        JsonNode data = root.path("data");
        JsonNode total = root.path("total");
        require(total.isIntegralNumber() && total.canConvertToInt() && data.isArray()
                && !data.isEmpty() && total.intValue() == data.size(), "复权因子总数不匹配或为空");
        NavigableMap<LocalDate, BigDecimal> result = new TreeMap<>();
        for (JsonNode node : data) {
            LocalDate date = date(node, "d", today);
            BigDecimal factor = number(node, "f");
            require(factor.signum() > 0, "复权因子必须为正数");
            require(result.putIfAbsent(date, factor) == null, "复权因子日期重复 " + date);
        }
        require(result.lastEntry().getValue().compareTo(BigDecimal.ONE) == 0, "最新复权因子不是 1，无法确认当前价格基准");
        return result;
    }

    private static EastMoneyKLine currentBar(String symbol, EastMoneyQuote quote, LocalDateTime fetchedAt) {
        LocalDate today = fetchedAt.toLocalDate();
        require(quote != null && symbol.equals(quote.symbol()) && today.equals(quote.tradeDate())
                && quote.marketTimestamp() != null, "缺少同股票同交易日的当前行情");
        LocalDateTime marketTime = LocalDateTime.ofInstant(quote.marketTimestamp(), TradingClockService.CHINA_MARKET_ZONE);
        require(today.equals(marketTime.toLocalDate()) && !marketTime.isAfter(fetchedAt), "行情源时间跨日或位于未来");
        LocalTime time = fetchedAt.toLocalTime();
        require(!time.isBefore(TradingClockService.MORNING_START), "开盘前无法确认当日日线");
        if (!time.isBefore(TradingClockService.REGULAR_CLOSE)) {
            require(!marketTime.toLocalTime().isBefore(TradingClockService.REGULAR_CLOSE), "行情源尚未到收盘时间");
        } else if (time.isAfter(TradingClockService.MORNING_END) && time.isBefore(TradingClockService.AFTERNOON_START)) {
            // Sources may refresh during lunch; an 11:30 snapshot may also remain frozen.
            require(!marketTime.isBefore(today.atTime(TradingClockService.MORNING_END).minus(QUOTE_MAX_AGE)),
                    "午休行情早于 11:28");
        } else {
            require(Duration.between(marketTime, fetchedAt).compareTo(QUOTE_MAX_AGE) <= 0, "当前行情超过 120 秒时效");
        }
        // Quote volume is already in hands; the latest QFQ factor is exactly one.
        EastMoneyKLine bar = new EastMoneyKLine(symbol, today, quote.openPrice(), quote.latestPrice(),
                quote.highPrice(), quote.lowPrice(), quote.volume(), quote.amount(), quote.turnoverRate());
        validateBar(bar);
        return bar;
    }

    private static void validateBar(EastMoneyKLine bar) {
        KLineSeriesIntegrity.Result result = KLineSeriesIntegrity.assess(
                List.of(bar), 1, 1, 0, 1, 1, bar.tradeDate(), bar.tradeDate());
        require(result.complete(), result.detail());
        require(bar.volume() != null && bar.volume().signum() >= 0, "成交量缺失或为负数");
        require(bar.amount() == null || bar.amount().signum() >= 0, "成交额为负数");
        require(bar.turnoverRate() == null || bar.turnoverRate().signum() >= 0, "换手率为负数");
    }

    private static String qualifiedSymbol(String symbol) {
        require(symbol != null && symbol.matches("[0-9]{6}"), "股票代码无效");
        if (symbol.startsWith("6")) return "sh" + symbol;
        if (symbol.startsWith("0") || symbol.startsWith("3")) return "sz" + symbol;
        if (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("92")) return "bj" + symbol;
        throw new IllegalArgumentException("股票市场无法确认");
    }

    private static JsonNode json(String payload) throws JsonProcessingException {
        require(payload != null && !payload.isBlank(), "响应内容为空");
        JsonNode node = JSON.readTree(payload);
        require(node != null, "响应内容为空");
        return node;
    }

    private static LocalDate date(JsonNode node, String field, LocalDate today) {
        JsonNode value = node.path(field);
        require(value.isTextual() && value.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "交易日期格式无效");
        LocalDate date = LocalDate.parse(value.textValue());
        require(!date.isAfter(today), "包含未来交易日期 " + date);
        return date;
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isNumber() || value.isTextual(), field + " 缺失或格式无效");
        return new BigDecimal(value.asText());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
