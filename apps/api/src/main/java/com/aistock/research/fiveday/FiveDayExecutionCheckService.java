package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.AsharePriceLimitRule;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static com.aistock.research.fiveday.FiveDayModels.*;

@Service
public class FiveDayExecutionCheckService {
    private static final String V2 = VERSION_V2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal PRICE_BASIS_TOLERANCE = new BigDecimal("0.5");
    private final TradingClockService clock;
    private final EastMoneyClient market;
    private final FiveDayContextEngine engine;
    private final FiveDayEventEvidenceService evidence;

    public FiveDayExecutionCheckService(TradingClockService clock, EastMoneyClient market,
                                       FiveDayContextEngine engine, FiveDayEventEvidenceService evidence) {
        this.clock = clock;
        this.market = market;
        this.engine = engine;
        this.evidence = evidence;
    }

    public ExecutionCheck check(Snapshot snapshot, Candidate candidate) {
        LocalDateTime localNow = clock.currentMarketDateTime();
        Instant checkedAt = instant(localNow);
        String symbol = candidate == null ? null : candidate.symbol();
        EventReview events = new EventReview("UNKNOWN", List.of(), List.of("本次公告尚未复核。"));
        if (snapshot == null || candidate == null || symbol == null || !symbol.matches("\\d{6}"))
            return notChecked(symbol, "UNKNOWN", checkedAt, events, "冻结快照或候选身份缺失。");
        if (!V2.equals(snapshot.ruleVersion()) || !snapshot.forwardEligible())
            return notChecked(symbol, "BLOCKED", checkedAt, events, "仅支持v2前瞻冻结信号的当日入场检查。");
        if (!"READY".equals(snapshot.status()))
            return notChecked(symbol, "WATCH", checkedAt, events, "冻结信号尚未就绪。");
        ExecutionCheck timeFailure = entryTimeFailure(candidate, localNow, events);
        if (timeFailure != null) return timeFailure;
        if (snapshot.signalDate() == null || clock.isMarketClosedDay(snapshot.signalDate())
                || clock.verifiedTradingDayAfter(snapshot.signalDate(), 0).isEmpty())
            return notChecked(symbol, "UNKNOWN", checkedAt, events, "冻结信号日期或交易日历无法确认。");
        if (!clock.verifiedTradingDayAfter(snapshot.signalDate(), 1).filter(candidate.entryDate()::equals).isPresent())
            return notChecked(symbol, "BLOCKED", checkedAt, events, "计划入场日并非信号后的第一个已核实交易日。");
        if (snapshot.context() == null || snapshot.context().assessedAt() == null || snapshot.generatedAt() == null)
            return notChecked(symbol, "UNKNOWN", checkedAt, events, "冻结上下文或冻结采集时点缺失。");
        Instant deadline = instant(candidate.entryDate().atTime(TradingClockService.OPEN_CALL_START));
        Instant signalCloseAt = instant(snapshot.signalDate().atTime(TradingClockService.REGULAR_CLOSE));
        if (!snapshot.context().assessedAt().isBefore(deadline) || !snapshot.generatedAt().isBefore(deadline)
                || snapshot.context().assessedAt().isBefore(signalCloseAt)
                || snapshot.generatedAt().isBefore(snapshot.context().assessedAt()))
            return notChecked(symbol, "BLOCKED", checkedAt, events, "冻结证据未处于信号收盘后至入场日09:15之前的前瞻窗口。");
        List<Candidate> frozenCandidates = snapshot.candidates().stream().filter(row -> symbol.equals(row.symbol())).toList();
        if (frozenCandidates.size() != 1 || !candidate.equals(frozenCandidates.get(0)))
            return notChecked(symbol, "UNKNOWN", checkedAt, events, "候选与冻结快照不唯一对应，不能使用外部替换的价格边界。");
        List<CandidateAssessment> assessments = snapshot.context().assessments().stream()
                .filter(row -> symbol.equals(row.symbol())).toList();
        if (assessments.size() != 1)
            return notChecked(symbol, "UNKNOWN", checkedAt, events, "未取得唯一对应的冻结候选判断。");
        String decision = assessments.get(0).decision();
        if (!"ELIGIBLE".equals(decision)) {
            String status = "AVOID".equals(decision) ? "BLOCKED" : "WATCH".equals(decision) ? "WATCH" : "UNKNOWN";
            return notChecked(symbol, status, checkedAt, events, "冻结候选未达到ELIGIBLE条件：" + decision + "。");
        }

        AshareQuoteSnapshot quotes;
        try {
            quotes = market.fetchAshareQuoteSnapshot(10000);
        } catch (RuntimeException exception) {
            return notChecked(symbol, "UNKNOWN", instant(clock.currentMarketDateTime()), events, "全市场实时报价源暂不可用。");
        }
        if (quotes == null)
            return notChecked(symbol, "UNKNOWN", instant(clock.currentMarketDateTime()), events, "全市场实时报价源未返回数据。");
        try {
            events = evidence.review(symbol, candidate.name(), candidate.industry(), instant(clock.currentMarketDateTime()));
        } catch (RuntimeException exception) {
            events = new EventReview("UNKNOWN", List.of(), List.of("最新公司公告暂不可用，不能解释为没有风险。"));
        }
        if (events == null) events = new EventReview("UNKNOWN", List.of(), List.of("最新公司公告未返回可确认结果。"));

        // Check freshness after both requests so provider latency cannot leave a stale passing result.
        localNow = clock.currentMarketDateTime();
        checkedAt = instant(localNow);
        timeFailure = entryTimeFailure(candidate, localNow, events);
        if (timeFailure != null) return timeFailure;
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (EastMoneyQuote quote : quotes.quotes()) {
            if (quote == null || quote.symbol() == null || !quote.symbol().matches("\\d{6}")) continue;
            if (unique.putIfAbsent(quote.symbol(), quote) != null) duplicates.add(quote.symbol());
        }
        duplicates.forEach(unique::remove);
        Instant quoteCheckedAt = checkedAt;
        LocalDate quoteDay = localNow.toLocalDate();
        List<BigDecimal> changes = unique.values().stream()
                .filter(quote -> usableForMarket(quote, quoteDay, quoteCheckedAt))
                .map(EastMoneyQuote::changePercent).toList();
        Market environment = engine.classifyMarket(quotes.expectedCount(), changes);
        List<String> notes = new ArrayList<>();
        notes.add("实时全市场环境：" + environment.state() + "；有效同日、5分钟内唯一报价 "
                + environment.coveredCount() + "/" + environment.expectedCount() + "，覆盖 " + environment.coveragePercent() + "%。");
        notes.addAll(environment.reasons());
        if ("UNKNOWN".equals(environment.state())) notes.add("实时全市场环境未完成核实，不能通过入场检查。");
        notes.add("本次结果仅反映检查时点的公开报价与公告线索，不构成下单指令。");
        if ("TITLE_RISK".equals(events.status()))
            return result(symbol, "BLOCKED", checkedAt, "NOT_CHECKED", events, notes, "最新公告存在标题风险线索，需阅读原文核实。");

        EastMoneyQuote quote = unique.get(symbol);
        if (quote == null || !fresh(quote, quoteDay, checkedAt))
            return result(symbol, "UNKNOWN", checkedAt, "UNKNOWN", events, notes,
                    "个股报价缺失、身份重复、日期错误或市场时间未知/未来/超过5分钟；不以采集时间替代市场时间。");
        if (!positive(quote.latestPrice()) || !positive(quote.openPrice()) || quote.changePercent() == null
                || quote.changePercent().compareTo(HUNDRED.negate()) <= 0 || quote.volume() == null || quote.volume().signum() < 0)
            return result(symbol, "UNKNOWN", checkedAt, "UNKNOWN", events, notes, "个股开盘价、现价、涨跌幅或成交量数据缺失/无效。");
        if (quote.volume().signum() == 0)
            return result(symbol, "BLOCKED", checkedAt, "NO_VOLUME", events, notes, "个股未确认存在当日成交量。");
        if (!positive(candidate.signalClose()) || !positive(candidate.stopPrice()) || !positive(candidate.maxEntryPrice())
                || candidate.stopPrice().compareTo(candidate.maxEntryPrice()) > 0)
            return result(symbol, "UNKNOWN", checkedAt, "UNKNOWN", events, notes, "冻结价格基准或入场边界缺失/无效。");
        BigDecimal previousClose = quote.latestPrice().divide(BigDecimal.ONE.add(quote.changePercent().movePointLeft(2)), 12, RoundingMode.HALF_UP);
        if (percent(previousClose, candidate.signalClose()).abs().compareTo(PRICE_BASIS_TOLERANCE) > 0)
            return result(symbol, "UNKNOWN", checkedAt, "UNKNOWN", events, notes,
                    "报价反推昨收与冻结收盘价的基准差异超过0.5%，可能存在复权或价格基准变化，不能应用旧边界。");
        if (AsharePriceLimitRule.isLimitUpLike(symbol, quote.changePercent())
                || AsharePriceLimitRule.isLimitUpLike(symbol, percent(quote.openPrice(), previousClose)))
            return result(symbol, "BLOCKED", checkedAt, "LIMIT_UP_LIKE", events, notes, "现价或开盘价达到涨停近似门槛，不能据此假定可成交。");
        if (outside(quote.latestPrice(), candidate) || outside(quote.openPrice(), candidate))
            return result(symbol, "BLOCKED", checkedAt, "OUT_OF_RANGE", events, notes, "开盘价或现价超出冻结失效位与最高入场价范围。");
        notes.add("个股市场时间：" + quote.marketTimestamp() + "；开盘价与现价均位于冻结入场边界内，价格基准差异不超过0.5%。");
        if (!"NO_TITLE_RISK".equals(events.status()))
            return result(symbol, "UNKNOWN", checkedAt, "IN_RANGE", events, notes, "最新公告覆盖无法确认，不能解释为没有风险。");
        if ("RISK_OFF".equals(environment.state()))
            return result(symbol, "BLOCKED", checkedAt, "IN_RANGE", events, notes, "实时全市场已触发风险环境门槛。");
        if ("UNKNOWN".equals(environment.state()))
            return result(symbol, "UNKNOWN", checkedAt, "IN_RANGE", events, notes, "实时全市场有效样本或覆盖不足。");
        if (!"NORMAL".equals(environment.state()))
            return result(symbol, "WATCH", checkedAt, "IN_RANGE", events, notes, "实时全市场仍为谨慎环境，仅观察。");
        return result(symbol, "PASSED", checkedAt, "IN_RANGE", events, notes, "当前入场前检查条件齐备；实际成交仍需由用户确认与记录。");
    }

    private ExecutionCheck entryTimeFailure(Candidate candidate, LocalDateTime now, EventReview events) {
        LocalDate today = now.toLocalDate();
        Instant checkedAt = instant(now);
        if (candidate.entryDate() == null || clock.verifiedTradingDayAfter(today, 0).isEmpty())
            return notChecked(candidate.symbol(), "UNKNOWN", checkedAt, events, "计划入场日期或当前交易日历未核实。");
        if (!candidate.entryDate().equals(today) || clock.isMarketClosedDay(today))
            return notChecked(candidate.symbol(), "BLOCKED", checkedAt, events, "当前日期不是候选的有效计划入场交易日。");
        LocalTime time = now.toLocalTime();
        boolean morning = !time.isBefore(TradingClockService.MORNING_START) && !time.isAfter(TradingClockService.MORNING_END);
        boolean afternoon = !time.isBefore(TradingClockService.AFTERNOON_START) && time.isBefore(TradingClockService.CLOSING_CALL_START);
        return morning || afternoon ? null : notChecked(candidate.symbol(), "WATCH", checkedAt, events,
                "入场前检查仅在计划当日09:30–11:30或13:00至14:57之前的连续竞价时段进行。");
    }

    private boolean usableForMarket(EastMoneyQuote quote, LocalDate today, Instant now) {
        return fresh(quote, today, now) && positive(quote.latestPrice()) && positive(quote.volume())
                && quote.changePercent() != null && quote.changePercent().compareTo(HUNDRED.negate()) > 0;
    }

    private boolean fresh(EastMoneyQuote quote, LocalDate today, Instant now) {
        return today.equals(quote.tradeDate()) && quote.marketTimestamp() != null
                && today.equals(quote.marketTimestamp().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate())
                && !quote.marketTimestamp().isAfter(now)
                && Duration.between(quote.marketTimestamp(), now).compareTo(Duration.ofMinutes(5)) <= 0;
    }

    private boolean outside(BigDecimal price, Candidate candidate) {
        return price.compareTo(candidate.stopPrice()) <= 0 || price.compareTo(candidate.maxEntryPrice()) > 0;
    }

    private ExecutionCheck notChecked(String symbol, String status, Instant checkedAt, EventReview events, String note) {
        return new ExecutionCheck(symbol, status, checkedAt, "NOT_CHECKED", events, List.of(note, "实时全市场环境尚未核实。"));
    }

    private ExecutionCheck result(String symbol, String status, Instant checkedAt, String priceState,
                                  EventReview events, List<String> notes, String note) {
        List<String> resultNotes = new ArrayList<>(notes);
        resultNotes.add(note);
        return new ExecutionCheck(symbol, status, checkedAt, priceState, events, resultNotes);
    }

    private static Instant instant(LocalDateTime value) { return value.atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant(); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.subtract(base).multiply(HUNDRED).divide(base, 12, RoundingMode.HALF_UP);
    }

    public record ExecutionCheck(String symbol, String status, Instant checkedAt, String priceState,
                                 EventReview events, List<String> notes) {
        public ExecutionCheck { notes = notes == null ? List.of() : List.copyOf(notes); }
    }
}
