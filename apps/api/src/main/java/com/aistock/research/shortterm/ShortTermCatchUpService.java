package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.EastMoneyRelatedBoard;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** On-demand shared-board observations, deliberately separate from the existing candidate strategy. */
@Service
public class ShortTermCatchUpService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_HISTORY_COVERAGE = new BigDecimal("80");
    private static final BigDecimal LOWER_MIDDLE = new BigDecimal("25");
    private static final BigDecimal UPPER_MIDDLE = new BigDecimal("75");
    private static final int DISPLAY_LIMIT = 20;
    private static final int FOLLOW_UP_LIMIT = 50;
    private static final long MAX_QUOTE_AGE_SECONDS = 120;
    private static final List<String> CAUTIONS = List.of(
            "供应商共同板块归属不证明共同事件受益、题材催化或资金转移。",
            "封板锚点只是观察对象；一次快照不能证明持续加单、排队买不到或资金轮动。",
            "候选仅为同板块上涨且未封板的观察，不生成买入指令，也不保证隔日补涨。"
    );
    private static final String FOLLOW_UP_EXPLANATION =
            "相对首次观察价的未调整价格变动，非实际成交收益；除权、分红等会影响可比性，不以最高价假设成交。";

    private final ShortTermLimitUpBoardService boardService;
    private final EastMoneyClient client;
    private final FirstBoardDailyBarStore dailyBars;
    private final TradingClockService tradingClock;
    private final ShortTermDailyHistoryService historyPreparation;

    public ShortTermCatchUpService(ShortTermLimitUpBoardService boardService, EastMoneyClient client,
                                  FirstBoardDailyBarStore dailyBars, TradingClockService tradingClock,
                                  ShortTermDailyHistoryService historyPreparation) {
        this.boardService = boardService;
        this.client = client;
        this.dailyBars = dailyBars;
        this.tradingClock = tradingClock;
        this.historyPreparation = historyPreparation;
    }

    public ShortTermCatchUpSnapshot snapshot(String industry, LocalDate date, String leaderSymbol, String boardCode) {
        if (industry == null || industry.isBlank()) throw new IllegalArgumentException("行业名称不能为空");
        if (date == null) throw new IllegalArgumentException("看板日期不能为空");
        requireSymbol(leaderSymbol);
        String industryName = industry.trim();
        String selectedCode = boardCode == null || boardCode.isBlank() ? null : boardCode.trim().toUpperCase(Locale.ROOT);
        Instant now = now();
        LocalDate nextDay = tradingClock.verifiedTradingDayAfter(date, 1).orElse(null);
        if (!date.equals(tradingClock.currentMarketDate())) {
            return empty(date, industryName, leaderSymbol, null, List.of(), null, "UNAVAILABLE", now, nextDay,
                    List.of("当前供应商归属不能重建历史归属；关联观察仅支持当前交易日。"));
        }
        if (!verifiedMarketDay(date)) {
            return empty(date, industryName, leaderSymbol, null, List.of(), null, "UNAVAILABLE", now, nextDay,
                    List.of("看板日不是已经日历核实的交易日。"));
        }
        ShortTermLimitUpBoardSnapshot board;
        try {
            board = boardService.snapshot(date);
        } catch (RuntimeException exception) {
            return empty(date, industryName, leaderSymbol, null, List.of(), null, "DATA_GAP", now, nextDay,
                    List.of("涨停池暂缺：" + message(exception)));
        }
        now = now();
        if (board == null || !board.available() || !date.equals(board.tradeDate()) || !fresh(board.fetchedAt(), now)) {
            return empty(date, industryName, leaderSymbol, null, List.of(), null, "DATA_GAP", now, nextDay,
                    List.of("同日新鲜涨停池不可用，不能核实锚点当前封板事实。"));
        }
        ShortTermLimitUpStock leader = board.stocks().stream()
                .filter(stock -> leaderSymbol.equals(stock.symbol()) && industryName.equals(industry(stock.industry())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("锚点必须属于所选行业当日涨停池"));
        List<EastMoneyRelatedBoard> relatedBoards;
        try {
            relatedBoards = client.fetchStockRelatedBoards(leaderSymbol);
            if (relatedBoards == null) relatedBoards = List.of();
        } catch (RuntimeException exception) {
            return empty(date, industryName, leaderSymbol, leader, List.of(), null, "DATA_GAP", now, nextDay,
                    List.of("供应商所属板块暂缺：" + message(exception)));
        }
        if (selectedCode == null) {
            return empty(date, industryName, leaderSymbol, leader, relatedBoards, null,
                    relatedBoards.isEmpty() ? "DATA_GAP" : "SELECT_BOARD", now, nextDay,
                    relatedBoards.isEmpty() ? List.of("供应商没有返回可核实的所属板块。") : List.of());
        }
        EastMoneyRelatedBoard selected = relatedBoards.stream().filter(value -> selectedCode.equals(value.code()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("所选板块不在锚点的供应商所属板块中"));
        AshareQuoteSnapshot quotes;
        try {
            quotes = client.fetchBoardQuoteSnapshot(selected.code());
        } catch (RuntimeException exception) {
            return empty(date, industryName, leaderSymbol, leader, relatedBoards, selected, "DATA_GAP", now, nextDay,
                    List.of("共同板块全量行情暂缺：" + message(exception)));
        }
        if (quotes == null) {
            return empty(date, industryName, leaderSymbol, leader, relatedBoards, selected, "DATA_GAP", now, nextDay,
                    List.of("共同板块全量行情没有返回快照。"));
        }
        now = now();
        List<String> gaps = new ArrayList<>();
        Map<String, EastMoneyQuote> members = new LinkedHashMap<>();
        int validQuotes = 0;
        for (EastMoneyQuote quote : quotes.quotes()) {
            if (quote == null || !validIdentity(quote)) {
                gaps.add("板块成分存在证券代码或名称缺失，不能核实完整成分身份。");
                continue;
            }
            if (members.putIfAbsent(quote.symbol(), quote) != null) {
                gaps.add("板块成分证券代码重复，不能核实全量覆盖。");
                continue;
            }
            if (validQuote(quote, date, now)) validQuotes++;
        }
        if (!quotes.complete() || quotes.expectedCount() <= 0 || quotes.fetchedCount() != quotes.expectedCount()
                || quotes.missingCount() != 0 || quotes.quotes().size() != quotes.expectedCount()
                || members.size() != quotes.expectedCount()) {
            gaps.add("板块成分未完整获取：预期 " + quotes.expectedCount() + "，返回 " + quotes.quotes().size()
                    + "；缺失行没有被当成零或从分母移除。");
        }
        if (!members.containsKey(leaderSymbol)) gaps.add("完整成分中没有锚点，供应商归属与成分未能相互核对。");
        if (!fresh(board.fetchedAt(), now)) gaps.add("拉取板块期间涨停池快照已超过120秒，需要重新核实锚点封板。");
        if (validQuotes != quotes.expectedCount()) {
            gaps.add("部分成分的同日价格、涨跌幅、有效成交量或源报价时间缺失/不一致/超过120秒，不能建立完整比较。");
        }
        if (!tradingClock.currentSession().regularAuctionOpen()) {
            gaps.add("当前不是竞价交易时段，不能把按需拉取的旧行情作为新鲜盘中观察。");
        }
        if (!gaps.isEmpty()) {
            return new ShortTermCatchUpSnapshot(date, industryName, leaderSymbol, selected.code(), selected.name(),
                    now, null, "DATA_GAP", leader, relatedBoards, quotes.source(),
                    quotes.expectedCount(), quotes.quotes().size(), validQuotes, 0, null,
                    null, null, 0, 0, 0, List.of(), nextDay, List.of(),
                    anchorEvidence(leader), CAUTIONS, distinct(gaps));
        }

        gaps.addAll(historyPreparation.ensureWindow(members.keySet(), date, 20));
        now = now();
        int stillFresh = 0;
        for (EastMoneyQuote quote : members.values()) if (validQuote(quote, date, now)) stillFresh++;
        if (stillFresh != members.size() || !fresh(board.fetchedAt(), now)
                || !date.equals(tradingClock.currentMarketDate()) || !tradingClock.currentSession().regularAuctionOpen()) {
            gaps.add("连续历史准备完成后原行情已超过新鲜度或竞价时段边界；请刷新取得新的封板及报价快照，已补日线会直接复用。");
            return new ShortTermCatchUpSnapshot(date, industryName, leaderSymbol, selected.code(), selected.name(),
                    now, null, "DATA_GAP", leader, relatedBoards, quotes.source(), quotes.expectedCount(),
                    quotes.quotes().size(), stillFresh, 0, null, null, null, 0, 0, 0, List.of(), nextDay,
                    List.of(), anchorEvidence(leader), CAUTIONS, distinct(gaps));
        }
        History history = history(members.keySet(), date);
        gaps.addAll(history.dataGaps());
        int historyCount = history.returns().size();
        BigDecimal historyCoverage = percent(historyCount, members.size());
        boolean percentileAvailable = historyCount >= 5
                && BigDecimal.valueOf(historyCount).multiply(HUNDRED)
                .compareTo(BigDecimal.valueOf(members.size()).multiply(MIN_HISTORY_COVERAGE)) >= 0;
        if (!percentileAvailable) gaps.add("有效历史样本不足5个或历史覆盖不足80%，不赋予收益分位及中位标签。");
        if (nextDay == null) gaps.add("下一交易日尚未经过交易日历核实。");
        Set<String> sealedSymbols = new HashSet<>();
        board.stocks().forEach(stock -> sealedSymbols.add(stock.symbol()));
        int rising = (int) members.values().stream().filter(row -> row.changePercent().signum() > 0).count();
        int sealed = (int) members.keySet().stream().filter(sealedSymbols::contains).count();
        BigDecimal leaderReturn = history.returns().get(leaderSymbol);
        List<ShortTermCatchUpCandidate> candidates = new ArrayList<>();
        for (EastMoneyQuote quote : members.values()) {
            if (sealedSymbols.contains(quote.symbol()) || quote.changePercent().signum() <= 0 || excludedName(quote.name())) continue;
            BigDecimal return5 = history.returns().get(quote.symbol());
            BigDecimal percentile = percentileAvailable && return5 != null ? percentile(return5, history.returns().values().stream().toList()) : null;
            boolean middle = percentile != null && percentile.compareTo(LOWER_MIDDLE) >= 0 && percentile.compareTo(UPPER_MIDDLE) <= 0;
            List<String> rowEvidence = new ArrayList<>(List.of("锚点及候选均在供应商所选板块完整成分内。",
                    "当前同日有效报价上涨，且不在该日涨停池封板名单中。"));
            List<String> rowGaps = new ArrayList<>();
            if (return5 != null) rowEvidence.add("前五日收益仅使用截至前一交易日的连续六个本地前复权收盘价。");
            else rowGaps.add("连续六个已完成交易日的本地前复权收盘价不完整，前五日收益未知。");
            if (middle) rowEvidence.add("前五日收益位于所选板块有效历史横截面的25%至75%分位。");
            if (percentile == null) rowGaps.add("历史收益分位未知，不能称为中位跟随。");
            if (leaderReturn == null) rowGaps.add("锚点前五日收益未知，收益差无法计算。");
            if (quote.amount() == null) rowGaps.add("成交额缺失。");
            if (quote.turnoverRate() == null) rowGaps.add("换手率缺失。");
            List<String> contrary = new ArrayList<>(List.of("上涨及共同归属不能证明补涨因果，也不保证隔日价格上涨。"));
            if (return5 != null && leaderReturn != null && return5.compareTo(leaderReturn) >= 0)
                contrary.add("候选前五日收益不低于锚点，不能据此认定存在相对滞涨。");
            candidates.add(new ShortTermCatchUpCandidate(quote.symbol(), quote.name(), quote.latestPrice(), quote.changePercent(),
                    quote.amount(), quote.turnoverRate(), quote.marketTimestamp(), return5, percentile,
                    return5 == null || leaderReturn == null ? null : return5.subtract(leaderReturn), middle,
                    return5 == null ? "UNKNOWN" : "VALID", rowEvidence, contrary, rowGaps));
        }
        candidates.sort(Comparator.comparing(ShortTermCatchUpCandidate::middle).reversed()
                .thenComparing(row -> row.returnPercentile() == null ? HUNDRED : row.returnPercentile().subtract(new BigDecimal("50")).abs())
                .thenComparing(ShortTermCatchUpCandidate::changePercent, Comparator.reverseOrder())
                .thenComparing(ShortTermCatchUpCandidate::amount, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ShortTermCatchUpCandidate::symbol));
        int eligible = candidates.size();
        List<ShortTermCatchUpCandidate> displayed = candidates.stream().limit(DISPLAY_LIMIT).toList();
        List<String> evidence = new ArrayList<>(anchorEvidence(leader));
        evidence.add("供应商板块全量成分 " + members.size() + "，同日有效报价 " + validQuotes + "，历史有效样本 " + historyCount + "。 ");
        evidence.add("候选范围：板块内上涨、未封板且名称不含ST/退市；符合 " + eligible + "，展示 " + displayed.size() + "，截去 " + (eligible - displayed.size()) + "。 ");
        evidence.add("中位为前五日收益横截面分位25%至75%，同收益使用并列中间秩；中位优先展示。");
        return new ShortTermCatchUpSnapshot(date, industryName, leaderSymbol, selected.code(), selected.name(), now,
                members.values().stream().map(EastMoneyQuote::marketTimestamp).min(Comparator.naturalOrder()).orElse(null),
                gaps.isEmpty() ? "READY" : "DATA_GAP", leader, relatedBoards, quotes.source(),
                quotes.expectedCount(), quotes.quotes().size(), validQuotes, historyCount, historyCoverage,
                rising, sealed, eligible, displayed.size(), eligible - displayed.size(), history.dates(), nextDay,
                displayed, evidence, CAUTIONS, distinct(gaps));
    }

    public ShortTermCatchUpFollowUpSnapshot followUp(ShortTermCatchUpFollowUpRequest request) {
        List<ShortTermCatchUpObservation> observations = request == null ? List.of() : request.observations();
        if (observations.size() > FOLLOW_UP_LIMIT) throw new IllegalArgumentException("单次最多查询50条首次观察");
        Set<String> ids = new HashSet<>();
        for (ShortTermCatchUpObservation observation : observations) {
            if (observation == null || observation.id() == null || observation.id().isBlank()
                    || observation.id().length() > 200 || !ids.add(observation.id()))
                throw new IllegalArgumentException("首次观察id不能为空、重复或超过200字符");
            requireSymbol(observation.symbol());
            requireSymbol(observation.leaderSymbol());
            if (observation.tradeDate() == null || !positive(observation.observedPrice()))
                throw new IllegalArgumentException("首次观察交易日及正观察价不能为空");
        }
        Instant now = now();
        LocalDate today = tradingClock.currentMarketDate();
        boolean active = tradingClock.currentSession().regularAuctionOpen();
        Set<String> symbols = new LinkedHashSet<>();
        for (ShortTermCatchUpObservation observation : observations) {
            LocalDate nextDay = nextObservationDay(observation.tradeDate());
            if (today.equals(nextDay) && active) symbols.add(observation.symbol());
        }
        Map<String, EastMoneyQuote> quotes = new LinkedHashMap<>();
        Set<String> duplicateSymbols = new HashSet<>();
        List<String> gaps = new ArrayList<>();
        if (!symbols.isEmpty()) {
            try {
                List<EastMoneyQuote> returned = client.fetchTencentQuotes(new ArrayList<>(symbols), FOLLOW_UP_LIMIT);
                if (returned != null) for (EastMoneyQuote quote : returned) {
                    if (quote != null && symbols.contains(quote.symbol()) && quotes.putIfAbsent(quote.symbol(), quote) != null)
                        duplicateSymbols.add(quote.symbol());
                }
            } catch (RuntimeException exception) {
                gaps.add("隔日行情暂缺：" + message(exception));
            }
        }
        now = now();
        today = tradingClock.currentMarketDate();
        active = tradingClock.currentSession().regularAuctionOpen();
        List<ShortTermCatchUpFollowUpResult> results = new ArrayList<>();
        for (ShortTermCatchUpObservation observation : observations) {
            LocalDate nextDay = nextObservationDay(observation.tradeDate());
            String status;
            EastMoneyQuote quote = null;
            BigDecimal change = null;
            List<String> rowGaps = new ArrayList<>();
            if (nextDay == null) {
                status = "UNKNOWN";
                rowGaps.add("首次观察日或下一交易日未通过交易日历核实。");
            } else if (today.isBefore(observation.tradeDate())) {
                status = "UNKNOWN";
                rowGaps.add("首次观察日期晚于当前日期。");
            } else if (today.isBefore(nextDay)) {
                status = "WAITING";
            } else if (today.isAfter(nextDay)) {
                status = "EXPIRED";
                rowGaps.add("已经超过精确下一交易日，不用更晚行情替代隔日反馈。");
            } else if (!active) {
                status = "UNKNOWN";
                rowGaps.add("精确下一交易日当前不在竞价交易时段，等待新鲜有效成交报价。");
            } else {
                quote = quotes.get(observation.symbol());
                if (duplicateSymbols.contains(observation.symbol()) || !validQuote(quote, nextDay, now)) {
                    status = "UNKNOWN";
                    rowGaps.add("隔日同日新鲜报价或有效成交量缺失（含停牌/零成交/混合日期/重复身份/超过120秒）。");
                } else {
                    status = "OBSERVED";
                    change = priceChange(quote.latestPrice(), observation.observedPrice());
                }
            }
            rowGaps.addAll(gaps);
            results.add(new ShortTermCatchUpFollowUpResult(observation.id(), observation.tradeDate(), nextDay,
                    observation.symbol(), observation.leaderSymbol(), observation.observedPrice(), status,
                    "OBSERVED".equals(status) ? quote.latestPrice() : null,
                    quote == null ? null : quote.tradeDate(), quote == null ? null : quote.marketTimestamp(),
                    change, FOLLOW_UP_EXPLANATION, rowGaps));
        }
        return new ShortTermCatchUpFollowUpSnapshot(now, results, gaps);
    }

    private History history(Set<String> symbols, LocalDate date) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = date;
        for (int index = 0; index < 6; index++) {
            cursor = tradingClock.previousTradingDay(cursor);
            if (!verifiedMarketDay(cursor) || !tradingClock.isCompletedDailyBar(cursor))
                return new History(List.of(), Map.of(), List.of("连续六个历史交易日尚未通过日历/完成状态核实。"));
            dates.add(cursor);
        }
        Collections.reverse(dates);
        List<Map<String, BigDecimal>> closes = new ArrayList<>();
        try {
            for (LocalDate day : dates) {
                Map<String, BigDecimal> values = dailyBars.closingPrices(symbols, day);
                closes.add(values == null ? Map.of() : values);
            }
        } catch (RuntimeException exception) {
            return new History(dates, Map.of(), List.of("本地日线读取失败，保留报价事实，历史收益未知：" + message(exception)));
        }
        Map<String, BigDecimal> returns = new LinkedHashMap<>();
        for (String symbol : symbols) {
            if (closes.stream().allMatch(values -> positive(values.get(symbol)))) {
                returns.put(symbol, priceChange(closes.get(5).get(symbol), closes.get(0).get(symbol)));
            }
        }
        return new History(dates, returns, List.of());
    }

    private ShortTermCatchUpSnapshot empty(LocalDate date, String industry, String symbol,
            ShortTermLimitUpStock leader, List<EastMoneyRelatedBoard> boards, EastMoneyRelatedBoard board,
            String status, Instant now, LocalDate nextDay, List<String> gaps) {
        return new ShortTermCatchUpSnapshot(date, industry, symbol, board == null ? null : board.code(),
                board == null ? null : board.name(), now, null, status, leader, boards, null,
                0, 0, 0, 0, null, null, null, 0, 0, 0, List.of(), nextDay, List.of(),
                leader == null ? List.of() : anchorEvidence(leader), CAUTIONS, gaps);
    }

    private static List<String> anchorEvidence(ShortTermLimitUpStock leader) {
        return List.of("锚点属于所选行业当日涨停池；当前封板事实来自涨停池快照。",
                "供应商归属仅表示所属板块，需用户自行核对题材与事件关系。",
                "锚点连板数 " + leader.consecutiveBoards() + "，首次封板 "
                        + (leader.firstSealTime() == null ? "未知" : leader.firstSealTime())
                        + "，开板次数 " + leader.sealBreakCount() + "。");
    }

    private boolean verifiedMarketDay(LocalDate date) {
        return date != null && tradingClock.verifiedTradingDayAfter(date, 0).isPresent() && !tradingClock.isMarketClosedDay(date);
    }

    private LocalDate nextObservationDay(LocalDate observedDate) {
        return verifiedMarketDay(observedDate) ? tradingClock.verifiedTradingDayAfter(observedDate, 1).orElse(null) : null;
    }

    private boolean validQuote(EastMoneyQuote quote, LocalDate date, Instant now) {
        return quote != null && validIdentity(quote) && date.equals(quote.tradeDate())
                && quote.marketTimestamp() != null
                && date.equals(quote.marketTimestamp().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate())
                && fresh(quote.marketTimestamp(), now) && positive(quote.latestPrice())
                && quote.changePercent() != null && positive(quote.volume());
    }

    private static boolean validIdentity(EastMoneyQuote quote) {
        return quote.symbol() != null && quote.symbol().matches("\\d{6}") && quote.name() != null && !quote.name().isBlank();
    }

    private static boolean fresh(Instant timestamp, Instant now) {
        return timestamp != null && !timestamp.isAfter(now)
                && Duration.between(timestamp, now).compareTo(Duration.ofSeconds(MAX_QUOTE_AGE_SECONDS)) <= 0;
    }

    private Instant now() {
        return tradingClock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean excludedName(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        return normalized.contains("ST") || normalized.contains("退");
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || !symbol.matches("\\d{6}")) throw new IllegalArgumentException("股票代码必须是6位数字");
    }

    private static String industry(String industry) {
        return industry == null || industry.isBlank() ? "未分类" : industry.trim();
    }

    private static BigDecimal priceChange(BigDecimal latest, BigDecimal base) {
        return latest.subtract(base).multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(int count, int total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentile(BigDecimal value, List<BigDecimal> values) {
        long less = values.stream().filter(other -> other.compareTo(value) < 0).count();
        long equal = values.stream().filter(other -> other.compareTo(value) == 0).count();
        BigDecimal rank = BigDecimal.valueOf(less).add(BigDecimal.valueOf(equal - 1).divide(new BigDecimal("2")));
        return rank.multiply(HUNDRED).divide(BigDecimal.valueOf(values.size() - 1L), 4, RoundingMode.HALF_UP);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record History(List<LocalDate> dates, Map<String, BigDecimal> returns, List<String> dataGaps) { }
}
