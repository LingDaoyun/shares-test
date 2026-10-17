package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardAlertStatus;
import com.aistock.research.firstboard.FirstBoardBaselineService;
import com.aistock.research.firstboard.FirstBoardBoard;
import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static com.aistock.research.fiveday.FiveDayModels.*;
import static com.aistock.research.fiveday.FiveDayContextModels.*;

@Service
public class FiveDayService {
    private static final LocalTime DATA_CUTOFF = LocalTime.of(15, 40);
    private static final int PRICE_HISTORY_WORKERS = 8;
    private final FirstBoardBaselineService baseline;
    private final FirstBoardDailyBarStore dailyBars;
    private final EastMoneyClient market;
    private final TradingClockService clock;
    private final FiveDayEvaluator evaluator;
    private final FiveDayHistoryService history;
    private final RecommendationAttestationService attestations;
    private final FiveDayContextEngine contextEngine;
    private final FiveDayEventEvidenceService evidenceService;
    private final FiveDayExecutionCheckService execution;
    private final Map<Key, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(3), task -> {
                Thread thread = new Thread(task, "five-day-scan"); thread.setDaemon(true); return thread;
            });

    public FiveDayService(FirstBoardBaselineService baseline, FirstBoardDailyBarStore dailyBars,
                         EastMoneyClient market, TradingClockService clock, FiveDayEvaluator evaluator,
                         FiveDayHistoryService history, RecommendationAttestationService attestations) {
        this(baseline, dailyBars, market, clock, evaluator, history, attestations, null, null, null);
    }

    @Autowired
    public FiveDayService(FirstBoardBaselineService baseline, FirstBoardDailyBarStore dailyBars,
                         EastMoneyClient market, TradingClockService clock, FiveDayEvaluator evaluator,
                         FiveDayHistoryService history, RecommendationAttestationService attestations,
                         FiveDayContextEngine contextEngine, FiveDayEventEvidenceService evidenceService,
                         FiveDayExecutionCheckService execution) {
        this.baseline = baseline; this.dailyBars = dailyBars; this.market = market; this.clock = clock;
        this.evaluator = evaluator; this.history = history; this.attestations = attestations;
        this.contextEngine = contextEngine; this.evidenceService = evidenceService; this.execution = execution;
    }

    public Snapshot snapshot(boolean growth, boolean star) { return snapshot(growth, star, VERSION); }
    public Snapshot snapshot(boolean growth, boolean star, String version) {
        Key key = key(growth, star, version);
        Snapshot current = snapshots.get(key);
        if (current != null) return current;
        return saved(key).orElseGet(() -> empty(key, "IDLE",
                "点击扫描，使用截至 " + key.date() + " 的完整日线；15:40 前使用上一交易日。", List.of()));
    }

    public Snapshot start(boolean growth, boolean star) { return start(growth, star, VERSION); }
    public synchronized Snapshot start(boolean growth, boolean star, String version) {
        Key key = key(growth, star, version);
        Snapshot current = snapshots.get(key);
        if (current != null && "SCANNING".equals(current.status())) return current;
        var saved = saved(key);
        if (saved.isPresent()) { snapshots.put(key, saved.get()); return saved.get(); }
        Snapshot started = empty(key, "SCANNING", "正在准备完整收盘基线", List.of());
        snapshots.put(key, started);
        snapshots.entrySet().removeIf(entry -> entry.getKey().date().isBefore(key.date().minusDays(7))
                && !"SCANNING".equals(entry.getValue().status()));
        try { executor.execute(() -> scan(key)); }
        catch (RejectedExecutionException e) {
            snapshots.put(key, empty(key, "FAILED", "已有扫描占用队列，请稍后重试", List.of()));
        }
        return snapshots.get(key);
    }

    private java.util.Optional<Snapshot> saved(Key key) {
        return VERSION.equals(key.version()) ? history.latest(key.date(), key.growth(), key.star())
                : history.latest(key.date(), key.growth(), key.star(), key.version());
    }
    private Key key(boolean growth, boolean star, String version) {
        if (!VERSION.equals(version) && !VERSION_V2.equals(version))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知五日观察版本");
        return new Key(signalDate(), growth, star, version);
    }

    private void scan(Key key) {
        try {
            Rules rules = Rules.defaults();
            LocalDate next = clock.verifiedTradingDayAfter(key.date(), 1).orElse(null);
            if (next == null || clock.verifiedTradingDayAfter(next, rules.holdingDays() - 1).isEmpty()) {
                snapshots.put(key, empty(key, "FAILED", "交易日历未覆盖完整五日持有期", List.of("不使用周末推算法替代已核实日历")));
                return;
            }
            var access = baseline.leaderAccess(next);
            if (access.status() != FirstBoardAlertStatus.READY || !key.date().equals(access.baselineDate())) {
                snapshots.put(key, empty(key, access.status() == FirstBoardAlertStatus.FAILED ? "FAILED" : "PREPARING",
                        "完整收盘基线正在准备；稍后点击重新扫描。不会使用其他交易日代替 " + key.date(), access.dataGaps()));
                return;
            }
            if (access.stocks().isEmpty()) {
                snapshots.put(key, empty(key, "FAILED", "收盘基线没有可复核股票，未保存为空候选成功样本", access.dataGaps()));
                return;
            }
            List<FirstBoardLeaderBaselineStock> stocks = access.stocks().values().stream()
                    .filter(stock -> inScope(stock, key) && key.date().equals(stock.baselineDate()))
                    .sorted(Comparator.comparing(FirstBoardLeaderBaselineStock::symbol)).toList();
            PricePreparation prices = preparePrices(key, stocks, rules, access.universeCount());
            List<Seed> seeds = new ArrayList<>(prices.seeds());
            Context context = null;
            PolicyBundle policies = null;
            List<CandidateAssessment> assessments = new ArrayList<>();
            if (VERSION_V2.equals(key.version())) {
                Map<String, StockBars> prepared = prices.stocks().stream().collect(Collectors.toMap(StockBars::symbol, row -> row));
                List<StockBars> marketStocks = access.stocks().values().stream()
                        .filter(stock -> inScope(stock, new Key(key.date(), true, true, key.version()))
                                && key.date().equals(stock.baselineDate()))
                        .map(stock -> {
                            StockBars cached = prepared.get(stock.symbol());
                            return cached != null && !cached.bars().isEmpty() ? cached : new StockBars(stock.symbol(),
                                    stock.name(), stock.industry(), dailyBars.recentBars(stock.symbol(), key.date(), 2));
                        }).toList();
                context = contextEngine.analyze(key.date(), now(), marketStocks, prices.stocks(), access.supportedCount());
                policies = evidenceService.fetchPolicies(now());
                Set<String> strong = context.sectors().stream().filter(sector -> "STRONG".equals(sector.state()))
                        .map(Sector::industry).collect(Collectors.toSet());
                seeds.removeIf(seed -> !strong.contains(industry(seed.stock().industry())));
                List<String> notes = new ArrayList<>(context.notes());
                notes.add("市场覆盖分母为完整基线支持范围" + access.supportedCount() + "只；技术基线成功" + access.stocks().size() + "只，失败或不足历史者不从市场覆盖分母中删除。");
                notes.add("本范围全部形态预选" + prices.seeds().size() + "只；主线行业内" + seeds.size()
                        + "只；非强行业过滤" + (prices.seeds().size() - seeds.size()) + "只。名额在强行业间轮询分配。");
                context = new Context(context.assessedAt(), context.market(), context.sectors(), List.of(), List.of(), "UNKNOWN", notes);
            }
            seeds.sort(Comparator.comparing(Seed::range).thenComparing(seed -> seed.stock().symbol()));
            List<Seed> selected = context == null ? seeds.stream().limit(rules.maximumReviewed()).toList()
                    : selectAcrossSectors(seeds, context, key.date(), rules.maximumReviewed());
            List<Candidate> candidates = new ArrayList<>();
            int missing = prices.missing();
            int reviewed = 0;
            for (Seed seed : selected) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("扫描已停止");
                FirstBoardLeaderBaselineStock stock = seed.stock();
                reviewed++;
                try {
                    List<EastMoneyKLine> bars = seed.bars();
                    if (!bars.isEmpty()) {
                        bars = market.enrichDailyKLineTradingMetrics(stock.symbol(), bars.get(0).tradeDate(), key.date(), bars);
                        dailyBars.upsertBars(bars, "五日观察：已存日K与东方财富成交额/换手补齐");
                    }
                    FiveDayEvaluator.Evaluation technical = evaluator.evaluate(stock, bars, List.of(), key.date(), rules, List.of());
                    if (technical.candidate() == null) {
                        if (!technical.dataGaps().isEmpty()) missing++;
                        continue;
                    }
                    LocalDate begin = key.date();
                    for (int i = 1; i < rules.flowDays(); i++) begin = clock.previousTradingDay(begin);
                    List<EastMoneyFundFlowSnapshot> funds = List.of();
                    List<String> gaps = new ArrayList<>();
                    try { funds = market.fetchFundFlowHistory(stock.symbol(), begin, key.date()); }
                    catch (RuntimeException e) { gaps.add("历史主力资金流获取失败，保留量价观察：" + rootMessage(e)); }
                    Candidate candidate = evaluator.evaluate(stock, bars, funds, key.date(), rules, gaps).candidate();
                    if (candidate != null) {
                        candidates.add(candidate);
                        if (context != null) assessments.add(assess(candidate, context, policies, key.date()));
                        if (!candidate.flowDataComplete()) missing++;
                    }
                } catch (RuntimeException e) {
                    missing++;
                } finally {
                    snapshots.put(key, new Snapshot("SCANNING", key.version(), key.date(), now(), false,
                            key.growth(), key.star(), "已复核 " + reviewed + " / " + selected.size() + " 只价格预选股",
                            rules, coverage(access.universeCount(), seeds.size(), reviewed, candidates, missing),
                            List.of(), List.of(), withEvidence(context, policies, assessments)));
                }
            }
            candidates.sort(Comparator.comparing(Candidate::matchesFundFlow).reversed()
                    .thenComparing(Candidate::rangePercent).thenComparing(Candidate::symbol));
            context = withEvidence(context, policies, assessments);
            Instant finished = now();
            boolean forward = clock.currentMarketDateTime().isBefore(next.atTime(9, 15));
            List<String> gaps = new ArrayList<>(prices.dataGaps());
            if (seeds.size() > reviewed) gaps.add((context == null ? "本次按振幅从窄到宽复核 " : "本次在强行业间轮询复核 ")
                    + reviewed + " 只价格预选股；其余 " + (seeds.size() - reviewed) + " 只未复核，不能解释为不符合条件。");
            if (missing > 0) gaps.add(missing + " 只已检查股票仍有数据缺失；缺失不当作价格不合格、零流入或零换手。");
            boolean environmentUnknown = context != null && "UNKNOWN".equals(context.market().state());
            boolean allSectorsUnknown = context != null && candidates.isEmpty()
                    && context.sectors().stream().allMatch(sector -> "UNKNOWN".equals(sector.state()));
            if (environmentUnknown || allSectorsUnknown || candidates.isEmpty() && missing > 0) {
                gaps.add(environmentUnknown ? "市场覆盖不足，当前观察尚未固化，可重新扫描。"
                        : allSectorsUnknown ? "行业判断全部未知或缺失，不能把空候选解释为没有合格股票；本次未固化，可重新扫描。"
                        : "数据覆盖尚不完整，不能把空候选解释为没有合格股票；本次未固化，可重新扫描。");
                snapshots.put(key, new Snapshot("FAILED", key.version(), key.date(), finished, false,
                        key.growth(), key.star(), "存在未补齐的关键数据，观察可查看；本次未固化，请稍后重新扫描。",
                        rules, coverage(access.universeCount(), seeds.size(), reviewed, candidates, missing), candidates, gaps, context));
                return;
            }
            if (!forward) gaps.add("扫描完成时间已过下一交易日09:15，仅为历史观察，不进入前瞻结果统计。");
            if (!candidates.isEmpty() && candidates.stream().noneMatch(Candidate::flowDataComplete)) {
                gaps.add("全部量价候选缺少完整资金数据；本次结果尚未固化，可以重新扫描，未进入前瞻样本统计。");
                snapshots.put(key, new Snapshot("READY", key.version(), key.date(), finished, false, key.growth(), key.star(),
                        "量价观察可查看；资金数据全部不完整，请稍后重新扫描。",
                        rules, coverage(access.universeCount(), seeds.size(), reviewed, candidates, missing), candidates, gaps, context));
                return;
            }
            Snapshot ready = new Snapshot("READY", key.version(), key.date(), finished, forward, key.growth(), key.star(),
                    "五日观察已生成；条件为研究初值，历史情景结果不代表实际成交或已证明的策略优势。",
                    rules, coverage(access.universeCount(), seeds.size(), reviewed, candidates, missing), candidates, gaps, context);
            history.capture(ready);
            snapshots.put(key, saved(key).orElse(ready));
        } catch (RuntimeException e) {
            snapshots.put(key, empty(key, "FAILED", "五日观察生成失败：" + rootMessage(e), List.of()));
        }
    }

    private PricePreparation preparePrices(Key key, List<FirstBoardLeaderBaselineStock> stocks,
                                           Rules rules, int universe) {
        List<Seed> seeds = new ArrayList<>();
        List<StockBars> contextStocks = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        int missing = 0;
        priceProgress(key, rules, universe, stocks.size(), 0, seeds.size(), missing);
        if (stocks.isEmpty()) return new PricePreparation(seeds, missing, List.of(), contextStocks);
        int workers = Math.min(PRICE_HISTORY_WORKERS, stocks.size());
        var pool = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "five-day-price-history");
            thread.setDaemon(true);
            return thread;
        });
        var completion = new ExecutorCompletionService<PriceCheck>(pool);
        try {
            int submitted = 0;
            while (submitted < workers) {
                FirstBoardLeaderBaselineStock stock = stocks.get(submitted++);
                completion.submit(() -> checkPrices(stock, key.date(), rules));
            }
            for (int checked = 1; checked <= stocks.size(); checked++) {
                PriceCheck result = completion.take().get();
                contextStocks.add(result.stock());
                if (result.gap() != null) {
                    missing++;
                    if (examples.size() < 5) examples.add(result.gap());
                } else if (result.seed() != null) seeds.add(result.seed());
                priceProgress(key, rules, universe, stocks.size(), checked, seeds.size(), missing);
                // Keep at most eight submitted jobs outstanding, including queued jobs.
                if (submitted < stocks.size()) {
                    FirstBoardLeaderBaselineStock stock = stocks.get(submitted++);
                    completion.submit(() -> checkPrices(stock, key.date(), rules));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连续日线检查已停止", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("连续日线检查失败", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
        List<String> gaps = new ArrayList<>();
        if (missing > 0) {
            gaps.add(missing + " 只股票在整段回补后仍缺少连续25个交易日的有效日线，未进入价格预选。");
            gaps.add("连续日线缺失示例（最多5只）：" + String.join("；", examples));
        }
        return new PricePreparation(seeds, missing, gaps, contextStocks);
    }

    private PriceCheck checkPrices(FirstBoardLeaderBaselineStock stock, LocalDate date, Rules rules) {
        try {
            int size = rules.recentTurnoverDays() + rules.priorTurnoverDays();
            List<EastMoneyKLine> bars = evaluator.exactWindow(stock.symbol(),
                    dailyBars.recentBars(stock.symbol(), date, size), date, size);
            if (bars.isEmpty()) {
                LocalDate begin = date;
                for (int i = 1; i < size; i++) begin = clock.previousTradingDay(begin);
                // Replace the entire price window; never splice newly rebased QFQ prices into old rows.
                List<EastMoneyKLine> fetched = market.fetchDailyKLines(stock.symbol(), begin, date);
                bars = evaluator.exactWindow(stock.symbol(), fetched, date, size);
                if (bars.isEmpty()) return new PriceCheck(null, stock.symbol() + "：整段回补后仍有缺日或无效OHLCV", stockBars(stock, List.of()));
                dailyBars.upsertBars(bars, "五日观察：连续25交易日日线整段回补");
            }
            BigDecimal range = evaluator.pricePreselection(stock, bars, date, rules).orElse(null);
            return new PriceCheck(range == null ? null : new Seed(stock, range, bars), null, stockBars(stock, bars));
        } catch (RuntimeException exception) {
            return new PriceCheck(null, stock.symbol() + "：" + rootMessage(exception), stockBars(stock, List.of()));
        }
    }

    private void priceProgress(Key key, Rules rules, int universe, int total, int checked, int qualified, int missing) {
        snapshots.put(key, new Snapshot("SCANNING", key.version(), key.date(), now(), false,
                key.growth(), key.star(), "补齐/检查连续日线 " + checked + "/" + total,
                rules, coverage(universe, qualified, 0, List.of(), missing), List.of(), List.of()));
    }

    public Attestation attestation(String symbol, boolean growth, boolean star) {
        return attestation(symbol, growth, star, VERSION);
    }
    public Attestation attestation(String symbol, boolean growth, boolean star, String version) {
        Snapshot current = snapshot(growth, star, version);
        if (!"READY".equals(current.status()) || !current.forwardEligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅前瞻观察快照可以关联本策略成交记录");
        }
        Candidate candidate = current.candidates().stream().filter(c -> c.symbol().equals(symbol)
                        && c.matchesFundFlow()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前没有资金条件合格的该股票"));
        if (candidate.entryDate() == null || clock.currentMarketDate().isAfter(candidate.entryDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划入场日期已过，请使用新的观察快照");
        }
        if (VERSION_V2.equals(version)) {
            var checked = execution.check(current, candidate);
            if (!"PASSED".equals(checked.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "入场前检查未通过：" + String.join("；", checked.notes()));
        }
        String token = attestations.register(VERSION_V2.equals(version) ? RecommendationSource.FIVE_DAY_CONTEXT_V2
                        : RecommendationSource.FIVE_DAY, candidate.symbol(), candidate.name(),
                VERSION_V2.equals(version) ? "FIVE_DAY_CONTEXT_V2" : "FIVE_DAY_OBSERVE", null, candidate.signalClose(), current.generatedAt(), candidate);
        return new Attestation(symbol, current.generatedAt(), token);
    }

    public FiveDayExecutionCheckService.ExecutionCheck executionCheck(String symbol, boolean growth, boolean star, String version) {
        Snapshot current = snapshot(growth, star, version);
        Candidate candidate = current.candidates().stream().filter(row -> row.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前快照中没有该股票"));
        return execution.check(current, candidate);
    }

    public TradeSchedule tradeSchedule(LocalDate entry) {
        LocalDate today = clock.currentMarketDate();
        if (entry == null || clock.isMarketClosedDay(entry)) return new TradeSchedule(entry, null, null,
                "INVALID_DATE", "买入日期不是市场交易日，请核对真实成交记录。");
        LocalDate target = clock.verifiedTradingDayAfter(entry, 4).orElse(null);
        if (target == null || clock.verifiedTradingDayAfter(today, 0).isEmpty()) return new TradeSchedule(entry, target, null,
                "CALENDAR_UNAVAILABLE", "已核实交易日历尚未覆盖该持有期，退出日期或进度待补充。");
        if (today.isBefore(entry)) return new TradeSchedule(entry, target, 0, "NOT_STARTED", "尚未到记录的买入日期。");
        int day = 0;
        for (LocalDate cursor = entry; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            if (clock.verifiedTradingDayAfter(cursor, 0).isEmpty()) return new TradeSchedule(entry, target, null,
                    "CALENDAR_UNAVAILABLE", "持仓区间存在未核实交易日历。");
            if (!clock.isMarketClosedDay(cursor)) day++;
        }
        String status = today.isAfter(target) ? "OVERDUE" : today.equals(target) ? "DUE" : "HOLDING";
        String detail = status.equals("OVERDUE") ? "已超过第5交易日计划期限；是否已退出以真实成交记录为准。"
                : status.equals("DUE") ? "今天为第5交易日，按计划处理退出；计划日期不代表已卖出。"
                : "买入日计第1天，第5交易日计划退出；风险条件触发时可提前处理。";
        return new TradeSchedule(entry, target, day, status, detail);
    }

    private List<Seed> selectAcrossSectors(List<Seed> seeds, Context context, LocalDate date, int limit) {
        Comparator<Seed> order = Comparator.<Seed, Boolean>comparing(seed ->
                        contextEngine.turning(seed.stock().symbol(), seed.bars(), date).confirmed()).reversed()
                .thenComparing(seed -> contextEngine.returnPercent(seed.stock().symbol(), seed.bars(), date, 3),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Seed::range).thenComparing(seed -> seed.stock().symbol());
        Map<String, List<Seed>> grouped = new LinkedHashMap<>();
        context.sectors().stream().filter(sector -> "STRONG".equals(sector.state()))
                .sorted(Comparator.comparingInt(Sector::rank).thenComparing(Sector::industry))
                .forEach(sector -> grouped.put(sector.industry(), seeds.stream()
                        .filter(seed -> sector.industry().equals(industry(seed.stock().industry()))).sorted(order).toList()));
        List<Seed> selected = new ArrayList<>();
        for (int round = 0; selected.size() < limit; round++) {
            int before = selected.size();
            for (List<Seed> group : grouped.values()) {
                if (round < group.size()) selected.add(group.get(round));
                if (selected.size() == limit) break;
            }
            if (selected.size() == before) break;
        }
        return selected;
    }

    private CandidateAssessment assess(Candidate candidate, Context context, PolicyBundle policies, LocalDate date) {
        Turning turning = contextEngine.turning(candidate.symbol(), candidate.bars(), date);
        EventReview events = evidenceService.review(candidate.symbol(), candidate.name(), candidate.industry(), now());
        String sectorState = context.sectors().stream().filter(row -> row.industry().equals(industry(candidate.industry())))
                .map(Sector::state).findFirst().orElse("UNKNOWN");
        List<String> reasons = new ArrayList<>();
        if (!"NORMAL".equals(context.market().state())) reasons.add("市场环境为 " + context.market().state() + "，暂不升级执行。");
        if (!"STRONG".equals(sectorState)) reasons.add("行业未达到持续强势条件：" + sectorState);
        if (!turning.confirmed()) reasons.addAll(turning.reasons());
        if (!candidate.flowDataComplete()) reasons.add("资金数据不完整。");
        else if (!candidate.matchesFundFlow()) reasons.add("资金条件未通过。");
        if (!"NO_TITLE_RISK".equals(events.status())) reasons.addAll(events.notes());
        String decision = "TITLE_RISK".equals(events.status()) ? "AVOID"
                : !candidate.flowDataComplete() || "UNKNOWN".equals(context.market().state())
                || "UNKNOWN".equals(sectorState) || !"NO_TITLE_RISK".equals(events.status()) ? "UNKNOWN"
                : reasons.isEmpty() ? "ELIGIBLE" : "WATCH";
        if ("ELIGIBLE".equals(decision)) reasons.add("市场、强行业、转强、量价资金与公告标题审阅条件齐备；入场当日仍须独立检查。");
        return new CandidateAssessment(candidate.symbol(), candidate.industry(), decision, turning, events,
                evidenceService.relatedPolicies(candidate.industry(), policies), reasons);
    }

    private Context withEvidence(Context base, PolicyBundle policies, List<CandidateAssessment> assessments) {
        if (base == null) return null;
        List<String> notes = new ArrayList<>(base.notes());
        notes.addAll(policies.notes());
        notes.add("政策为行业关联线索，不保证个股受益；消息覆盖限于配置政策源与巨潮公司公告标题。");
        return new Context(now(), base.market(), base.sectors(), assessments, policies.evidence(), policies.status(), notes);
    }
    private StockBars stockBars(FirstBoardLeaderBaselineStock stock, List<EastMoneyKLine> bars) {
        return new StockBars(stock.symbol(), stock.name(), stock.industry(), bars);
    }
    private String industry(String value) { return value == null || value.isBlank() ? "行业未知" : value.trim(); }
    private Instant now() { return clock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant(); }

    private boolean inScope(FirstBoardLeaderBaselineStock stock, Key key) {
        if (stock.name() == null || stock.board() == null) return false;
        String name = stock.name().toUpperCase(Locale.ROOT);
        if (name.contains("ST") || name.contains("退")) return false;
        return stock.board() == FirstBoardBoard.MAIN
                || stock.board() == FirstBoardBoard.GROWTH && key.growth()
                || stock.board() == FirstBoardBoard.STAR && key.star();
    }
    private LocalDate signalDate() {
        LocalDateTime now = clock.currentMarketDateTime();
        return clock.isMarketClosedDay(now.toLocalDate()) || now.toLocalTime().isBefore(DATA_CUTOFF)
                ? clock.previousTradingDay(now.toLocalDate()) : now.toLocalDate();
    }
    private Snapshot empty(Key key, String status, String message, List<String> gaps) {
        return new Snapshot(status, key.version(), key.date(), now(), false, key.growth(), key.star(), message,
                Rules.defaults(), new Coverage(0, 0, 0, 0, 0, 0, 0), List.of(), gaps);
    }
    private Coverage coverage(int universe, int prices, int reviewed, List<Candidate> candidates, int missing) {
        return new Coverage(universe, prices, reviewed, candidates.size(),
                (int)candidates.stream().filter(Candidate::matchesFundFlow).count(), missing, Math.max(0, prices - reviewed));
    }
    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable; while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
    @PreDestroy void close() { executor.shutdownNow(); }
    private record Key(LocalDate date, boolean growth, boolean star, String version) { }
    private record Seed(FirstBoardLeaderBaselineStock stock, BigDecimal range, List<EastMoneyKLine> bars) { }
    private record PriceCheck(Seed seed, String gap, StockBars stock) { }
    private record PricePreparation(List<Seed> seeds, int missing, List<String> dataGaps, List<StockBars> stocks) { }
}
