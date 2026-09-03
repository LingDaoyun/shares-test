package com.aistock.research.leader;

import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EastMoneyDragonTigerGateway implements DragonTigerGateway {

    private static final String PROFILE_REPORT = "RPT_DAILYBILLBOARD_PROFILE";
    private static final String BUY_REPORT = "RPT_BILLBOARD_DAILYDETAILSBUY";
    private static final String SELL_REPORT = "RPT_BILLBOARD_DAILYDETAILSSELL";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 100;
    private static final long CACHE_SECONDS = 300;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final TradingClockService tradingClock;
    private final ReportFetcher reportFetcher;
    private final Map<LocalDate, CachedRange> cache = new ConcurrentHashMap<>();

    @Autowired
    public EastMoneyDragonTigerGateway(
            RestClient restClient,
            ObjectMapper objectMapper,
            TradingClockService tradingClock
    ) {
        this(objectMapper, tradingClock,
                (report, page, start, end) -> fetchRemote(restClient, report, page, start, end));
    }

    EastMoneyDragonTigerGateway(
            ObjectMapper objectMapper,
            TradingClockService tradingClock,
            ReportFetcher reportFetcher
    ) {
        this.objectMapper = objectMapper;
        this.tradingClock = tradingClock;
        this.reportFetcher = reportFetcher;
    }

    @Override
    public DragonTigerBatch fetch(LocalDate scanTradeDate, Collection<String> requestedSymbols) {
        List<String> symbols = normalizeSymbols(requestedSymbols);
        if (symbols.isEmpty()) {
            return new DragonTigerBatch(null, Map.of(), List.of());
        }
        if (scanTradeDate == null) {
            return failed(symbols, "龙虎榜扫描日期缺失");
        }
        try {
            ParsedRange parsed = cachedRange(scanTradeDate);
            return select(scanTradeDate, symbols, parsed);
        } catch (RuntimeException exception) {
            return failed(symbols, "龙虎榜数据获取失败：" + rootMessage(exception));
        }
    }

    private ParsedRange cachedRange(LocalDate scanDate) {
        Instant now = Instant.now();
        CachedRange cached = cache.get(scanDate);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.parsed();
        }
        LocalDate start = scanDate;
        for (int index = 0; index < 9; index++) {
            start = tradingClock.previousTradingDay(start);
        }
        ParsedRange parsed = parseRange(
                fetchAll(PROFILE_REPORT, start, scanDate),
                fetchAll(BUY_REPORT, start, scanDate),
                fetchAll(SELL_REPORT, start, scanDate));
        cache.put(scanDate, new CachedRange(parsed, now.plusSeconds(CACHE_SECONDS)));
        return parsed;
    }

    private List<JsonNode> fetchAll(String report, LocalDate start, LocalDate end) {
        JsonNode first = requireResponse(reportFetcher.fetch(report, 1, start, end), report);
        List<JsonNode> rows = new ArrayList<>();
        addRows(first, rows);
        int pages = Math.min(MAX_PAGES, Math.max(1, first.path("result").path("pages").asInt(1)));
        for (int page = 2; page <= pages; page++) {
            addRows(requireResponse(reportFetcher.fetch(report, page, start, end), report), rows);
        }
        return List.copyOf(rows);
    }

    private JsonNode requireResponse(JsonNode response, String report) {
        if (response == null || (response.has("success") && !response.path("success").asBoolean())) {
            throw new IllegalStateException(report + " 返回失败");
        }
        JsonNode result = response.path("result");
        if (!result.isMissingNode() && !result.isNull() && !result.isObject()) {
            throw new IllegalStateException(report + " 返回结构异常");
        }
        return response;
    }

    private void addRows(JsonNode response, List<JsonNode> target) {
        JsonNode data = response.path("result").path("data");
        if (data.isArray()) {
            data.forEach(target::add);
        }
    }

    private ParsedRange parseRange(
            List<JsonNode> profileRows,
            List<JsonNode> buyRows,
            List<JsonNode> sellRows
    ) {
        Map<DisclosureKey, Profile> profiles = new LinkedHashMap<>();
        for (JsonNode row : profileRows) {
            DisclosureKey key = key(row);
            if (key == null) {
                continue;
            }
            BigDecimal buy = decimal(row, "BILLBOARD_BUY_AMT", "BUY_AMT", "BUY");
            BigDecimal sell = decimal(row, "BILLBOARD_SELL_AMT", "SELL_AMT", "SELL");
            BigDecimal net = decimal(row, "BILLBOARD_NET_AMT", "NET_AMT", "NET");
            if (net == null && buy != null && sell != null) {
                net = buy.subtract(sell);
            }
            BigDecimal dayAmount = decimal(row, "ACCUM_AMOUNT", "TOTAL_AMOUNT", "DEAL_AMOUNT");
            profiles.put(key, new Profile(key, net, dayAmount));
        }
        Map<SeatGroupKey, Map<String, SeatRow>> grouped = new LinkedHashMap<>();
        parseSeats(buyRows, "BUY", grouped);
        parseSeats(sellRows, "SELL", grouped);
        Map<DisclosureKey, List<SeatRow>> seats = new LinkedHashMap<>();
        grouped.forEach((group, rows) -> seats
                .computeIfAbsent(group.disclosure(), ignored -> new ArrayList<>())
                .addAll(rows.values()));
        seats.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparing(SeatRow::side).thenComparing(SeatRow::identity))
                .toList());
        return new ParsedRange(Map.copyOf(profiles), Map.copyOf(seats));
    }

    private void parseSeats(
            List<JsonNode> rows,
            String side,
            Map<SeatGroupKey, Map<String, SeatRow>> grouped
    ) {
        for (JsonNode row : rows) {
            DisclosureKey disclosure = key(row);
            if (disclosure == null) {
                continue;
            }
            String code = text(row, "OPERATEDEPT_CODE", "BRANCH_CODE");
            String name = text(row, "OPERATEDEPT_NAME", "BRANCH_NAME");
            String identity = code == null ? normalizeName(name) : "CODE:" + code;
            if (identity == null) {
                continue;
            }
            BigDecimal buy = zero(decimal(row, "BUY", "BUY_AMT", "BUY_AMOUNT"));
            BigDecimal sell = zero(decimal(row, "SELL", "SELL_AMT", "SELL_AMOUNT"));
            BigDecimal net = decimal(row, "NET", "NET_AMT", "NET_AMOUNT");
            if (net == null) {
                net = buy.subtract(sell);
            }
            SeatRow parsed = new SeatRow(
                    side, code, name, identity, buy, sell, net,
                    institution(name), stockConnect(name));
            grouped.computeIfAbsent(new SeatGroupKey(disclosure, side), ignored -> new LinkedHashMap<>())
                    .merge(identity, parsed, EastMoneyDragonTigerGateway::mergeSeat);
        }
    }

    private DragonTigerBatch select(LocalDate scanDate, List<String> symbols, ParsedRange parsed) {
        LocalDate oldestAdjustable = scanDate;
        for (int index = 0; index < 4; index++) {
            oldestAdjustable = tradingClock.previousTradingDay(oldestAdjustable);
        }
        Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> evidence = new LinkedHashMap<>();
        LocalDate latest = null;
        for (String symbol : symbols) {
            Profile profile = parsed.profiles().values().stream()
                    .filter(value -> value.key().symbol().equals(symbol))
                    .filter(value -> !value.key().tradeDate().isAfter(scanDate))
                    .max(Comparator.comparing(value -> value.key().tradeDate()))
                    .orElse(null);
            if (profile == null) {
                evidence.put(symbol, evidence(
                        PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED,
                        null, BigDecimal.ZERO, List.of(), List.of()));
                continue;
            }
            LocalDate disclosureDate = profile.key().tradeDate();
            latest = latest == null || disclosureDate.isAfter(latest) ? disclosureDate : latest;
            List<SeatRow> seatRows = parsed.seats().getOrDefault(profile.key(), List.of());
            List<PotentialLeaderSnapshot.Seat> outwardSeats = seatRows.stream()
                    .map(EastMoneyDragonTigerGateway::toSeat)
                    .toList();
            if (disclosureDate.isBefore(oldestAdjustable)) {
                evidence.put(symbol, evidence(
                        PotentialLeaderSnapshot.DisclosureStatus.STALE_DISCLOSURE,
                        disclosureDate, BigDecimal.ZERO, outwardSeats,
                        List.of("仅找到五个交易日窗口之外的历史龙虎榜披露")));
                continue;
            }
            evidence.put(symbol, evidence(
                    PotentialLeaderSnapshot.DisclosureStatus.DISCLOSED,
                    disclosureDate, adjustment(profile, seatRows), outwardSeats, List.of()));
        }
        return new DragonTigerBatch(latest, evidence, List.of());
    }

    BigDecimal adjustment(Profile profile, List<SeatRow> rows) {
        List<SeatRow> buys = rows.stream()
                .filter(row -> "BUY".equals(row.side()) && row.buyAmount().signum() > 0)
                .toList();
        Set<String> buyKeys = buys.stream().map(SeatRow::identity)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        BigDecimal branchPoints = switch (buyKeys.size()) {
            case 0, 1 -> BigDecimal.ZERO;
            case 2 -> BigDecimal.ONE;
            case 3 -> new BigDecimal("2");
            default -> new BigDecimal("3");
        };
        BigDecimal netPoints = netBuyPoints(profile.netAmount(), profile.dayAmount());
        BigDecimal concentration = concentration(buys);
        BigDecimal concentrationPoints = concentrationPoints(concentration);
        Set<String> sellKeys = rows.stream()
                .filter(row -> "SELL".equals(row.side()) && row.sellAmount().signum() > 0)
                .map(SeatRow::identity)
                .collect(java.util.stream.Collectors.toSet());
        long overlapCount = buyKeys.stream().filter(sellKeys::contains).count();
        BigDecimal overlapPoints = buyKeys.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(overlapCount)
                .divide(BigDecimal.valueOf(buyKeys.size()), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("-2"));
        BigDecimal entropyPoints = entropy(buys).multiply(new BigDecimal("2"));
        return LeaderRuleSet.scale(LeaderRuleSet.clamp(
                branchPoints.add(netPoints).add(concentrationPoints)
                        .add(overlapPoints).add(entropyPoints),
                LeaderRuleSet.MIN_SEAT_ADJUSTMENT, LeaderRuleSet.MAX_SEAT_ADJUSTMENT));
    }

    private BigDecimal netBuyPoints(BigDecimal net, BigDecimal dayAmount) {
        if (net == null || dayAmount == null || dayAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = net.divide(dayAmount, 8, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("-0.03")) <= 0) {
            return new BigDecimal("-2");
        }
        if (ratio.signum() < 0) {
            return ratio.add(new BigDecimal("0.03"))
                    .divide(new BigDecimal("0.03"), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("2"))
                    .subtract(new BigDecimal("2"));
        }
        return LeaderRuleSet.linear(
                ratio, BigDecimal.ZERO, new BigDecimal("0.05"), new BigDecimal("3"));
    }

    private BigDecimal concentration(List<SeatRow> buys) {
        BigDecimal total = buys.stream().map(SeatRow::buyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return buys.stream().map(SeatRow::buyAmount).max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO).divide(total, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal concentrationPoints(BigDecimal concentration) {
        if (concentration.compareTo(new BigDecimal("0.60")) >= 0) return new BigDecimal("-3");
        if (concentration.compareTo(new BigDecimal("0.50")) >= 0) return new BigDecimal("-2");
        if (concentration.compareTo(new BigDecimal("0.40")) >= 0) return new BigDecimal("-1");
        if (concentration.compareTo(new BigDecimal("0.20")) >= 0) return new BigDecimal("2");
        return BigDecimal.ONE;
    }

    private BigDecimal entropy(List<SeatRow> buys) {
        if (buys.size() <= 1) {
            return BigDecimal.ZERO;
        }
        double total = buys.stream().map(SeatRow::buyAmount)
                .mapToDouble(BigDecimal::doubleValue).sum();
        if (total <= 0D) {
            return BigDecimal.ZERO;
        }
        double entropy = 0D;
        for (SeatRow buy : buys) {
            double probability = buy.buyAmount().doubleValue() / total;
            if (probability > 0D) {
                entropy -= probability * Math.log(probability);
            }
        }
        return BigDecimal.valueOf(entropy / Math.log(buys.size()));
    }

    private DragonTigerBatch failed(List<String> symbols, String message) {
        Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> evidence = new LinkedHashMap<>();
        for (String symbol : symbols) {
            evidence.put(symbol, evidence(
                    PotentialLeaderSnapshot.DisclosureStatus.FETCH_FAILED,
                    null, BigDecimal.ZERO, List.of(), List.of(message)));
        }
        return new DragonTigerBatch(null, evidence, List.of(message));
    }

    private PotentialLeaderSnapshot.DragonTigerEvidence evidence(
            PotentialLeaderSnapshot.DisclosureStatus status,
            LocalDate date,
            BigDecimal adjustment,
            List<PotentialLeaderSnapshot.Seat> seats,
            List<String> gaps
    ) {
        return new PotentialLeaderSnapshot.DragonTigerEvidence(
                status, date, LeaderRuleSet.scale(adjustment), seats, gaps);
    }

    private static PotentialLeaderSnapshot.Seat toSeat(SeatRow row) {
        return new PotentialLeaderSnapshot.Seat(
                row.side(), row.branchCode(), row.branchName(), row.buyAmount(), row.sellAmount(),
                row.netAmount(), row.institution(), row.stockConnect());
    }

    private DisclosureKey key(JsonNode row) {
        String symbol = LeaderDirectionEngine.normalizeSymbol(text(row, "SECURITY_CODE", "SECUCODE"));
        LocalDate date = date(text(row, "TRADE_DATE", "TRADE_DATE_STR"));
        return symbol.isBlank() || date == null ? null : new DisclosureKey(symbol, date);
    }

    private static SeatRow mergeSeat(SeatRow left, SeatRow right) {
        return new SeatRow(
                left.side(), first(left.branchCode(), right.branchCode()),
                first(left.branchName(), right.branchName()), left.identity(),
                left.buyAmount().max(right.buyAmount()), left.sellAmount().max(right.sellAmount()),
                maxAbs(left.netAmount(), right.netAmount()),
                left.institution() || right.institution(), left.stockConnect() || right.stockConnect());
    }

    private static JsonNode fetchRemote(
            RestClient restClient,
            String report,
            int page,
            LocalDate start,
            LocalDate end
    ) {
        JsonNode response = restClient.get()
                .uri(builder -> builder
                        .scheme("https")
                        .host("datacenter-web.eastmoney.com")
                        .path("/api/data/v1/get")
                        .queryParam("reportName", report)
                        .queryParam("columns", "ALL")
                        .queryParam("pageNumber", page)
                        .queryParam("pageSize", PAGE_SIZE)
                        .queryParam("sortColumns", "TRADE_DATE")
                        .queryParam("sortTypes", "-1")
                        .queryParam("source", "WEB")
                        .queryParam("client", "WEB")
                        .queryParam("filter", "(TRADE_DATE>='" + start + "')(TRADE_DATE<='" + end + "')")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException(report + " 返回空响应");
        }
        return response;
    }

    private String text(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String normalized = value.asText().trim();
                if (!normalized.isBlank() && !"-".equals(normalized)) {
                    return normalized;
                }
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode row, String... fields) {
        String value = text(row, fields);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, DATE_TIME).toLocalDate();
            } catch (DateTimeParseException nested) {
                return null;
            }
        }
    }

    private List<String> normalizeSymbols(Collection<String> symbols) {
        if (symbols == null) {
            return List.of();
        }
        return symbols.stream()
                .map(LeaderDirectionEngine::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "NAME:" + value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static boolean institution(String name) {
        return name != null && name.contains("机构专用");
    }

    private static boolean stockConnect(String name) {
        return name != null && (name.contains("沪股通") || name.contains("深股通") || name.contains("股通专用"));
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal maxAbs(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.abs().compareTo(right.abs()) >= 0 ? left : right;
    }

    private static String first(String left, String right) {
        return left == null || left.isBlank() ? right : left;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    @FunctionalInterface
    interface ReportFetcher {
        JsonNode fetch(String report, int page, LocalDate start, LocalDate end);
    }

    record Profile(DisclosureKey key, BigDecimal netAmount, BigDecimal dayAmount) {
    }

    record SeatRow(
            String side,
            String branchCode,
            String branchName,
            String identity,
            BigDecimal buyAmount,
            BigDecimal sellAmount,
            BigDecimal netAmount,
            boolean institution,
            boolean stockConnect
    ) {
    }

    private record DisclosureKey(String symbol, LocalDate tradeDate) {
    }

    private record SeatGroupKey(DisclosureKey disclosure, String side) {
    }

    private record ParsedRange(
            Map<DisclosureKey, Profile> profiles,
            Map<DisclosureKey, List<SeatRow>> seats
    ) {
    }

    private record CachedRange(ParsedRange parsed, Instant expiresAt) {
    }
}
