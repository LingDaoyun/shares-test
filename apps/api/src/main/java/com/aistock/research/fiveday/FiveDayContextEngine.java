package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import static com.aistock.research.fiveday.FiveDayContextModels.*;

@Component
public class FiveDayContextEngine {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal VOLUME_RATIO = new BigDecimal("1.1");
    private static final String UNKNOWN_INDUSTRY = "行业未知";
    private final TradingClockService clock;

    public FiveDayContextEngine(TradingClockService clock) { this.clock = Objects.requireNonNull(clock); }

    public Context analyze(LocalDate date, Instant assessedAt, List<StockBars> marketStocks, List<StockBars> scopeStocks) {
        List<StockBars> market = uniqueStocks(marketStocks);
        return analyze(date, assessedAt, market, scopeStocks, market.size());
    }

    public Context analyze(LocalDate date, Instant assessedAt, List<StockBars> marketStocks,
                           List<StockBars> scopeStocks, int expectedMarketCount) {
        List<StockBars> market = uniqueStocks(marketStocks);
        List<StockBars> scope = uniqueStocks(scopeStocks);
        boolean completedAtAssessment = completedAt(date, assessedAt);
        List<BigDecimal> changes = new ArrayList<>();
        if (completedAtAssessment) {
            for (StockBars stock : market) {
                BigDecimal change = returnPercent(stock.symbol(), stock.bars(), date, 1);
                if (change != null) changes.add(change);
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add("市场日线信号日：" + date + "；行业日线信号日：" + date + "。仅使用该日及以前已完成日线。");
        notes.add("市场与行业阈值为待验证研究初值；成交量参与度先计算同股量比，不代表成交额、换手率或政策受益。");
        if (!completedAtAssessment) notes.add("信号日尚未完成、采集时点早于收盘或交易日历未覆盖，环境数据不能通过。");
        return new Context(assessedAt, classifyMarket(expectedMarketCount, changes), sectors(scope, date, completedAtAssessment),
                List.of(), List.of(), "UNKNOWN", notes);
    }

    /** Changes must already be checked for unique security identity and the same valid quote date/time. */
    public Market classifyMarket(int expectedCount, List<BigDecimal> dailyChanges) {
        List<BigDecimal> changes = dailyChanges == null ? List.of()
                : dailyChanges.stream().filter(Objects::nonNull).sorted().toList();
        int expected = expectedCount;
        int covered = changes.size();
        BigDecimal coverage = share(covered, expected);
        int advancing = (int) changes.stream().filter(change -> change.signum() > 0).count();
        int severe = (int) changes.stream().filter(change -> change.compareTo(new BigDecimal("-7")) <= 0).count();
        BigDecimal breadth = covered == 0 ? null : share(advancing, covered);
        BigDecimal severePercent = covered == 0 ? null : share(severe, covered);
        BigDecimal median = covered == 0 ? null : covered % 2 == 1 ? changes.get(covered / 2)
                : changes.get(covered / 2 - 1).add(changes.get(covered / 2)).divide(BigDecimal.valueOf(2));
        List<String> reasons = new ArrayList<>();
        String state;
        if (covered < 200 || !coverageAtLeastEighty(covered, expected) || covered > expected) {
            state = "UNKNOWN";
            if (covered < 200) reasons.add("有效同日日线/报价样本不足200只。");
            if (!coverageAtLeastEighty(covered, expected)) reasons.add("支持范围有效覆盖不足80%。");
            if (covered > expected) reasons.add("有效样本多于预期范围，不能确认覆盖口径。");
        } else if ((advancing * 100L < covered * 35L && median.signum() < 0)
                || severe * 100L >= covered * 8L) {
            state = "RISK_OFF";
            if (advancing * 100L < covered * 35L && median.signum() < 0)
                reasons.add("上涨广度不足35%且收益中位数为负。");
            if (severe * 100L >= covered * 8L) reasons.add("单日跌幅至少7%的样本占比达到8%。");
        } else if (advancing * 100L >= covered * 50L && median.signum() >= 0) {
            state = "NORMAL";
            reasons.add("上涨广度至少50%、收益中位数非负，且未触发风险环境门槛。");
        } else {
            state = "CAUTION";
            reasons.add("未达到正常环境门槛，维持观察。");
        }
        return new Market(state, expected, covered, rounded(coverage), rounded(breadth), rounded(median),
                rounded(severePercent), reasons);
    }

    public Turning turning(String symbol, List<EastMoneyKLine> bars, LocalDate date) {
        List<EastMoneyKLine> window = exactWindow(symbol, bars, date, 5);
        if (window.isEmpty()) return new Turning(false, null, null, List.of("最近5个连续交易日已完成日线缺失或OHLCV无效，不能确认转强。"));
        EastMoneyKLine last = window.get(4);
        BigDecimal closeVsMa = percent(last.close(), average(window.stream().map(EastMoneyKLine::close).toList()));
        BigDecimal range = last.high().subtract(last.low());
        BigDecimal position = range.signum() == 0 ? null : ratio(last.close().subtract(last.low()).multiply(HUNDRED), range);
        List<String> reasons = new ArrayList<>();
        if (last.close().compareTo(window.get(3).close()) <= 0) reasons.add("信号日收盘未高于前收盘。");
        if (closeVsMa.signum() < 0) reasons.add("信号日收盘低于近5日均价。");
        if (position == null) reasons.add("信号日最高价等于最低价，不作转强。");
        else if (position.compareTo(new BigDecimal("60")) < 0) reasons.add("信号日收盘未处于当日振幅上部60%。");
        boolean confirmed = reasons.isEmpty();
        if (confirmed) reasons.add("收盘高于前收盘、达到近5日均价且位于当日振幅上部至少60%。");
        return new Turning(confirmed, rounded(closeVsMa), rounded(position), reasons);
    }

    public BigDecimal returnPercent(String symbol, List<EastMoneyKLine> bars, LocalDate date, int days) {
        if (days < 1 || bars == null || days >= bars.size()) return null;
        List<EastMoneyKLine> window = exactWindow(symbol, bars, date, days + 1);
        return window.isEmpty() ? null : percent(window.get(days).close(), window.get(0).close());
    }

    private List<Sector> sectors(List<StockBars> stocks, LocalDate date, boolean completedAtAssessment) {
        Map<String, List<StockBars>> members = new TreeMap<>();
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (StockBars stock : stocks) {
            members.computeIfAbsent(stock.industry(), ignored -> new ArrayList<>()).add(stock);
            List<EastMoneyKLine> window = completedAtAssessment ? exactWindow(stock.symbol(), stock.bars(), date, 25) : List.of();
            if (!window.isEmpty()) {
                BigDecimal recentVolume = window.subList(20, 25).stream().map(EastMoneyKLine::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal previousVolume = window.subList(0, 20).stream().map(EastMoneyKLine::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
                boolean expanding = recentVolume.multiply(BigDecimal.valueOf(20))
                        .compareTo(previousVolume.multiply(BigDecimal.valueOf(5)).multiply(VOLUME_RATIO)) >= 0;
                samples.put(stock.symbol(), new Sample(percent(window.get(24).close(), window.get(21).close()),
                        percent(window.get(24).close(), window.get(19).close()), expanding));
            }
        }
        BigDecimal benchmark3 = average(samples.values().stream().map(Sample::return3).toList());
        BigDecimal benchmark5 = average(samples.values().stream().map(Sample::return5).toList());
        List<SectorData> data = new ArrayList<>();
        for (Map.Entry<String, List<StockBars>> entry : members.entrySet()) {
            List<Sample> covered = entry.getValue().stream().map(stock -> samples.get(stock.symbol())).filter(Objects::nonNull).toList();
            boolean valid = !UNKNOWN_INDUSTRY.equals(entry.getKey()) && covered.size() >= 5
                    && coverageAtLeastEighty(covered.size(), entry.getValue().size());
            data.add(new SectorData(entry.getKey(), entry.getValue(), covered, valid,
                    average(covered.stream().map(Sample::return3).toList()),
                    average(covered.stream().map(Sample::return5).toList())));
        }
        List<SectorData> qualified = data.stream().filter(SectorData::valid)
                .sorted(Comparator.comparing(SectorData::return5).reversed().thenComparing(SectorData::industry)).toList();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int rank = 0;
        BigDecimal priorReturn = null;
        for (int i = 0; i < qualified.size(); i++) {
            SectorData sector = qualified.get(i);
            if (priorReturn == null || sector.return5().compareTo(priorReturn) != 0) rank = i + 1;
            ranks.put(sector.industry(), rank);
            priorReturn = sector.return5();
        }
        List<Sector> result = new ArrayList<>();
        for (SectorData sector : data) {
            int covered = sector.samples().size();
            int advancing = (int) sector.samples().stream().filter(sample -> sample.return5().signum() > 0).count();
            int expanding = (int) sector.samples().stream().filter(Sample::expanding).count();
            BigDecimal excess3 = covered == 0 ? null : sector.return3().subtract(benchmark3);
            BigDecimal excess5 = covered == 0 ? null : sector.return5().subtract(benchmark5);
            List<String> reasons = new ArrayList<>();
            int sectorRank = ranks.getOrDefault(sector.industry(), 0);
            String state;
            if (!sector.valid()) {
                state = "UNKNOWN";
                if (UNKNOWN_INDUSTRY.equals(sector.industry())) reasons.add("冻结基线行业归属未知。");
                if (covered < 5) reasons.add("完整25个连续交易日日线样本不足5只。");
                if (!coverageAtLeastEighty(covered, sector.members().size())) reasons.add("冻结行业成员完整日线覆盖不足80%。");
            } else {
                if (sector.return5().signum() <= 0) reasons.add("行业5日等权收益未为正。");
                if (excess3.signum() <= 0 || excess5.signum() <= 0) reasons.add("行业3日和5日收益未同时超过本范围同覆盖样本的等权基准。");
                if (advancing * 100L < covered * 55L) reasons.add("5日上涨成员占比不足55%。");
                if (expanding * 100L < covered * 50L) reasons.add("近5日均量/此前20日均量达到1.1的成员占比不足50%。");
                if (sectorRank > (qualified.size() + 3) / 4) reasons.add("5日收益排名不在数据合格行业前25%。");
                state = reasons.isEmpty() ? "STRONG" : "WEAK";
                if (reasons.isEmpty()) reasons.add("3/5日持续超额、5日上涨广度、同股放量参与度及前25%排名均达到门槛。");
            }
            result.add(new Sector(sector.industry(), state, sectorRank, sector.members().size(), covered,
                    rounded(share(covered, sector.members().size())), rounded(sector.return3()), rounded(sector.return5()),
                    rounded(excess3), rounded(excess5), covered == 0 ? null : rounded(share(advancing, covered)),
                    covered == 0 ? null : rounded(share(expanding, covered)),
                    sector.members().stream().map(StockBars::symbol).sorted().toList(), reasons));
        }
        return result.stream().sorted(Comparator.comparingInt((Sector sector) -> sector.rank() == 0 ? Integer.MAX_VALUE : sector.rank())
                .thenComparing(Sector::industry)).toList();
    }

    private List<StockBars> uniqueStocks(List<StockBars> source) {
        Map<String, StockBars> stocks = new LinkedHashMap<>();
        Set<String> conflictingIndustries = new HashSet<>();
        for (StockBars stock : source == null ? List.<StockBars>of() : source) {
            if (stock == null || stock.symbol() == null || stock.symbol().isBlank()) continue;
            String industry = stock.industry() == null || stock.industry().isBlank() ? UNKNOWN_INDUSTRY : stock.industry().trim();
            StockBars prior = stocks.get(stock.symbol());
            if (prior == null) stocks.put(stock.symbol(), new StockBars(stock.symbol(), stock.name(), industry, stock.bars()));
            else {
                if (!prior.industry().equals(industry)) conflictingIndustries.add(stock.symbol());
                List<EastMoneyKLine> bars = new ArrayList<>(prior.bars());
                bars.addAll(stock.bars());
                stocks.put(stock.symbol(), new StockBars(prior.symbol(), prior.name(), prior.industry(), bars));
            }
        }
        for (String symbol : conflictingIndustries) {
            StockBars stock = stocks.get(symbol);
            stocks.put(symbol, new StockBars(symbol, stock.name(), stock.industry(), List.of()));
        }
        return List.copyOf(stocks.values());
    }

    private List<EastMoneyKLine> exactWindow(String symbol, List<EastMoneyKLine> source, LocalDate date, int size) {
        if (symbol == null || symbol.isBlank() || source == null || source.size() < size || !completed(date)) return List.of();
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = date;
        for (int i = 0; i < size; i++) {
            if (clock.verifiedTradingDayAfter(cursor, 0).isEmpty()) return List.of();
            dates.add(0, cursor);
            cursor = clock.previousTradingDay(cursor);
        }
        Set<LocalDate> required = new HashSet<>(dates);
        Map<LocalDate, EastMoneyKLine> byDate = new LinkedHashMap<>();
        for (EastMoneyKLine bar : source) {
            if (bar == null || !symbol.equals(bar.symbol()) || bar.tradeDate() == null || !required.contains(bar.tradeDate())) continue;
            if (!validBar(bar)) return List.of();
            EastMoneyKLine prior = byDate.putIfAbsent(bar.tradeDate(), bar);
            if (prior != null && !sameOhlcv(prior, bar)) return List.of();
        }
        if (byDate.size() != size) return List.of();
        return dates.stream().map(byDate::get).toList();
    }

    private boolean completed(LocalDate date) {
        return date != null && !clock.isMarketClosedDay(date)
                && clock.verifiedTradingDayAfter(date, 0).isPresent() && clock.isCompletedDailyBar(date);
    }

    private boolean completedAt(LocalDate date, Instant assessedAt) {
        if (!completed(date) || assessedAt == null) return false;
        LocalDateTime time = LocalDateTime.ofInstant(assessedAt, TradingClockService.CHINA_MARKET_ZONE);
        return time.toLocalDate().isAfter(date) || (time.toLocalDate().equals(date)
                && !time.toLocalTime().isBefore(TradingClockService.REGULAR_CLOSE));
    }

    private boolean validBar(EastMoneyKLine bar) {
        return positive(bar.open()) && positive(bar.close()) && positive(bar.high()) && positive(bar.low()) && positive(bar.volume())
                && bar.high().compareTo(bar.low()) >= 0 && bar.high().compareTo(bar.open().max(bar.close())) >= 0
                && bar.low().compareTo(bar.open().min(bar.close())) <= 0;
    }

    private boolean sameOhlcv(EastMoneyKLine first, EastMoneyKLine second) {
        return first.open().compareTo(second.open()) == 0 && first.close().compareTo(second.close()) == 0
                && first.high().compareTo(second.high()) == 0 && first.low().compareTo(second.low()) == 0
                && first.volume().compareTo(second.volume()) == 0;
    }

    private static boolean coverageAtLeastEighty(int covered, int expected) { return expected > 0 && covered * 100L >= expected * 80L; }
    private static BigDecimal share(int count, int total) { return total <= 0 ? BigDecimal.ZERO : ratio(BigDecimal.valueOf(count).multiply(HUNDRED), BigDecimal.valueOf(total)); }
    private static BigDecimal ratio(BigDecimal value, BigDecimal base) { return value.divide(base, 12, RoundingMode.HALF_UP); }
    private static BigDecimal percent(BigDecimal value, BigDecimal base) { return ratio(value.subtract(base).multiply(HUNDRED), base); }
    private static BigDecimal average(List<BigDecimal> values) { return values.isEmpty() ? null : ratio(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(values.size())); }
    private static BigDecimal rounded(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.HALF_UP); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private record Sample(BigDecimal return3, BigDecimal return5, boolean expanding) { }
    private record SectorData(String industry, List<StockBars> members, List<Sample> samples, boolean valid,
                              BigDecimal return3, BigDecimal return5) { }
}
