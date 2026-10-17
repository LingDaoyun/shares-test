package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class FiveDayContextEngineTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 7);
    private static final Instant AS_OF = Instant.parse("2026-09-07T08:00:00Z");
    private final TradingClockService clock = new TradingClockService(Clock.fixed(AS_OF, TradingClockService.CHINA_MARKET_ZONE));
    private final FiveDayContextEngine engine = new FiveDayContextEngine(clock);

    @Test void fiftyPercentBreadthAndNonNegativeMedianAreNormal() {
        Market result = market(100, 100, 0, 0);
        assertThat(result.state()).isEqualTo("NORMAL");
        assertThat(result.advancingPercent()).isEqualByComparingTo("50");
        assertThat(result.medianChangePercent()).isEqualByComparingTo("0.5");
    }

    @Test void preEntryAndDailyAnalysisUseTheSameMarketThresholds() {
        List<BigDecimal> changes = new ArrayList<>();
        for (int i = 0; i < 200; i++) changes.add(d(i < 16 ? "-7" : "1"));
        assertThat(engine.classifyMarket(250, changes).state()).isEqualTo("RISK_OFF");
        assertThat(engine.classifyMarket(251, changes).state()).isEqualTo("UNKNOWN");
        assertThat(engine.classifyMarket(199, changes).state()).isEqualTo("UNKNOWN");
    }

    @Test void lowBreadthWithNegativeMedianIsRiskOff() {
        assertThat(market(60, 0, 140, 0).state()).isEqualTo("RISK_OFF");
        assertThat(market(70, 0, 130, 0).state()).isEqualTo("CAUTION");
        assertThat(market(80, 120, 0, 0).state()).isEqualTo("CAUTION");
    }

    @Test void exactlyEightPercentLosingSevenPercentIsRiskOffDespitePositiveBreadth() {
        Market result = market(184, 0, 0, 16);
        assertThat(result.state()).isEqualTo("RISK_OFF");
        assertThat(result.severeDeclinePercent()).isEqualByComparingTo("8");
    }

    @Test void marketNeedsTwoHundredActualSamplesAndEightyPercentCoverage() {
        List<StockBars> source = population(200, "行业", 0, new int[]{100, 100, 100, 100, 101}, "1");
        for (int i = 200; i < 250; i++) source.add(new StockBars(symbol(i), "缺失", "行业", List.of()));
        Market atBoundary = analyze(source, List.of()).market();
        assertThat(atBoundary.state()).isEqualTo("NORMAL");
        assertThat(atBoundary.coveragePercent()).isEqualByComparingTo("80");
        source.add(new StockBars(symbol(250), "缺失", "行业", List.of()));
        assertThat(analyze(source, List.of()).market().state()).isEqualTo("UNKNOWN");
        assertThat(analyze(source.subList(0, 199), List.of()).market().state()).isEqualTo("UNKNOWN");
    }

    @Test void marketCoverageUsesOriginalSupportedCountBeforeBaselineExclusions() {
        List<StockBars> source = population(300, "行业", 0, up(), "1.2");
        Market result = engine.analyze(DATE, AS_OF, source, List.of(), 500).market();
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.expectedCount()).isEqualTo(500);
        assertThat(result.coveredCount()).isEqualTo(300);
        assertThat(result.coveragePercent()).isEqualByComparingTo("60");
    }

    @Test void unknownOrContradictoryOriginalMarketCountCannotBecomeObservedSampleCount() {
        List<StockBars> source = population(300, "行业", 0, up(), "1.2");
        for (int expected : new int[]{0, -1, 200}) {
            Market result = engine.analyze(DATE, AS_OF, source, List.of(), expected).market();
            assertThat(result.state()).isEqualTo("UNKNOWN");
            assertThat(result.expectedCount()).isEqualTo(expected);
        }
    }

    @Test void duplicateStocksAndIdenticalBarsDoNotInflateCoverage() {
        List<StockBars> source = population(200, "行业", 0, up(), "1.2");
        StockBars first = source.get(0);
        List<EastMoneyKLine> duplicateBars = new ArrayList<>(first.bars());
        duplicateBars.add(first.bars().get(24));
        source.set(0, new StockBars(first.symbol(), first.name(), first.industry(), duplicateBars));
        source.add(first);
        Market result = analyze(source, List.of()).market();
        assertThat(result.expectedCount()).isEqualTo(200);
        assertThat(result.coveredCount()).isEqualTo(200);
        assertThat(result.state()).isEqualTo("NORMAL");
    }

    @Test void conflictingBarsAndInvalidOhlcvCannotContributeSamples() {
        List<StockBars> source = population(200, "行业", 0, up(), "1.2");
        StockBars first = source.get(0);
        List<EastMoneyKLine> conflict = new ArrayList<>(first.bars());
        conflict.add(bar(first.symbol(), DATE, d("106"), d("120")));
        source.set(0, new StockBars(first.symbol(), first.name(), first.industry(), conflict));
        assertThat(analyze(source, List.of()).market().coveredCount()).isEqualTo(199);
        assertThat(analyze(source, List.of()).market().state()).isEqualTo("UNKNOWN");
        EastMoneyKLine invalid = new EastMoneyKLine(first.symbol(), DATE, d("105"), d("105"), d("104"), d("103"), d("0"), null);
        source.set(0, replaceLast(first, invalid));
        assertThat(analyze(source, List.of()).market().coveredCount()).isEqualTo(199);
    }

    @Test void sustainedSectorMustBeatSameCoverageEqualWeightedBenchmark() {
        List<StockBars> source = fourSectors();
        Sector hot = sector(source, "甲");
        assertThat(hot.state()).isEqualTo("STRONG");
        assertThat(hot.rank()).isEqualTo(1);
        assertThat(hot.return5Percent()).isEqualByComparingTo("5");
        assertThat(hot.return3Percent()).isEqualByComparingTo("2.9412");
        assertThat(hot.excess5Percent()).isEqualByComparingTo("3.75");
        assertThat(hot.advancing5Percent()).isEqualByComparingTo("100");
        assertThat(hot.volumeExpansionPercent()).isEqualByComparingTo("100");
        assertThat(sector(source, "乙").state()).isEqualTo("WEAK");
    }

    @Test void aOneDayRiseCannotReplacePositiveThreeDayExcess() {
        List<StockBars> source = fourSectors();
        source.subList(0, 5).clear();
        source.addAll(population(5, "甲", 0, new int[]{109, 110, 109, 104, 105}, "1.2"));
        Sector hot = sector(source, "甲");
        assertThat(hot.return5Percent()).isPositive();
        assertThat(hot.return3Percent()).isNegative();
        assertThat(hot.state()).isEqualTo("WEAK");
    }

    @Test void sectorNeedsFiveCoveredMembersAndEightyPercentOfFrozenMembership() {
        List<StockBars> source = fourSectors();
        source.add(new StockBars(symbol(80), "缺失", "甲", List.of()));
        assertThat(sector(source, "甲").state()).isEqualTo("STRONG");
        source.add(new StockBars(symbol(81), "缺失", "甲", List.of()));
        assertThat(sector(source, "甲").state()).isEqualTo("UNKNOWN");
        assertThat(sector(fourSectors().subList(0, 4), "甲").state()).isEqualTo("UNKNOWN");
    }

    @Test void missingTradingDayCannotBeReplacedByAnOlderBar() {
        List<StockBars> source = fourSectors();
        StockBars first = source.get(0);
        List<EastMoneyKLine> missing = new ArrayList<>(first.bars());
        missing.remove(22);
        missing.add(0, bar(first.symbol(), clock.previousTradingDay(first.bars().get(0).tradeDate()), d("100"), d("100")));
        source.set(0, new StockBars(first.symbol(), first.name(), first.industry(), missing));
        assertThat(sector(source, "甲").coveredCount()).isEqualTo(4);
        assertThat(sector(source, "甲").state()).isEqualTo("UNKNOWN");
        assertThat(engine.returnPercent(first.symbol(), missing, DATE, 3)).isNull();
    }

    @Test void participationUsesIndividualVolumeRatiosRatherThanRawShareSums() {
        List<StockBars> source = fourSectors();
        for (int i = 0; i < 5; i++) {
            StockBars old = source.get(i);
            BigDecimal base = d(i < 2 ? "1000000000" : "1");
            List<EastMoneyKLine> volumes = new ArrayList<>();
            for (int j = 0; j < 25; j++) {
                EastMoneyKLine row = old.bars().get(j);
                BigDecimal volume = j >= 20 && i < 2 ? base.multiply(d("100")) : base;
                volumes.add(new EastMoneyKLine(row.symbol(), row.tradeDate(), row.open(), row.close(), row.high(), row.low(), volume, row.amount()));
            }
            source.set(i, new StockBars(old.symbol(), old.name(), old.industry(), volumes));
        }
        Sector result = sector(source, "甲");
        assertThat(result.volumeExpansionPercent()).isEqualByComparingTo("40");
        assertThat(result.state()).isEqualTo("WEAK");
    }

    @Test void exactVolumeExpansionBoundaryPassesAndThinPriceBreadthDoesNot() {
        List<StockBars> source = fourSectors();
        source.subList(0, 5).clear();
        source.addAll(population(5, "甲", 0, up(), "1.1"));
        assertThat(sector(source, "甲").state()).isEqualTo("STRONG");
        source.removeIf(s -> s.industry().equals("甲"));
        source.addAll(population(3, "甲", 0, flat(), "1.2"));
        source.addAll(population(2, "甲", 3, new int[]{110, 120, 130, 140, 150}, "1.2"));
        Sector result = sector(source, "甲");
        assertThat(result.return5Percent()).isPositive();
        assertThat(result.advancing5Percent()).isEqualByComparingTo("40");
        assertThat(result.state()).isEqualTo("WEAK");
    }

    @Test void onlyTopQuarterOfDataQualifiedIndustriesCanBeStrong() {
        List<StockBars> source = new ArrayList<>();
        source.addAll(population(5, "甲", 0, new int[]{102, 104, 106, 108, 110}, "1.2"));
        source.addAll(population(5, "乙", 10, up(), "1.2"));
        source.addAll(population(5, "丙", 20, flat(), "1.2"));
        source.addAll(population(5, "丁", 30, flat(), "1.2"));
        assertThat(sector(source, "甲").state()).isEqualTo("STRONG");
        Sector second = sector(source, "乙");
        assertThat(second.excess3Percent()).isPositive();
        assertThat(second.excess5Percent()).isPositive();
        assertThat(second.rank()).isEqualTo(2);
        assertThat(second.state()).isEqualTo("WEAK");
    }

    @Test void futureBarsCannotChangeHistoricalContextOrTurningOrReturns() {
        List<StockBars> original = fourSectors();
        List<StockBars> withFuture = original.stream().map(stock -> {
            List<EastMoneyKLine> bars = new ArrayList<>(stock.bars());
            bars.add(bar(stock.symbol(), clock.nextTradingDay(DATE), d("1000"), d("100000")));
            return new StockBars(stock.symbol(), stock.name(), stock.industry(), bars);
        }).toList();
        assertThat(analyze(List.of(), withFuture).sectors()).isEqualTo(analyze(List.of(), original).sectors());
        assertThat(engine.turning(original.get(0).symbol(), withFuture.get(0).bars(), DATE))
                .isEqualTo(engine.turning(original.get(0).symbol(), original.get(0).bars(), DATE));
        assertThat(engine.returnPercent(original.get(0).symbol(), withFuture.get(0).bars(), DATE, 5)).isEqualByComparingTo("5");
    }

    @Test void turningRequiresARiseMa5AndUpperSixtyPercentClose() {
        StockBars rising = population(1, "甲", 0, up(), "1.2").get(0);
        assertThat(engine.turning(rising.symbol(), rising.bars(), DATE).confirmed()).isTrue();
        StockBars notRising = population(1, "甲", 0, new int[]{101, 102, 103, 105, 105}, "1.2").get(0);
        assertThat(engine.turning(notRising.symbol(), notRising.bars(), DATE).confirmed()).isFalse();
        StockBars belowMa = population(1, "甲", 0, new int[]{109, 109, 109, 104, 105}, "1.2").get(0);
        assertThat(engine.turning(belowMa.symbol(), belowMa.bars(), DATE).confirmed()).isFalse();
        EastMoneyKLine atBoundary = new EastMoneyKLine(rising.symbol(), DATE, d("104"), d("105"), d("107"), d("102"), d("100"), null);
        assertThat(engine.turning(rising.symbol(), replaceLast(rising, atBoundary).bars(), DATE).confirmed()).isTrue();
        EastMoneyKLine lowClose = new EastMoneyKLine(rising.symbol(), DATE, d("104"), d("105"), d("108"), d("102"), d("100"), null);
        assertThat(engine.turning(rising.symbol(), replaceLast(rising, lowClose).bars(), DATE).confirmed()).isFalse();
        EastMoneyKLine flatRange = new EastMoneyKLine(rising.symbol(), DATE, d("105"), d("105"), d("105"), d("105"), d("100"), null);
        assertThat(engine.turning(rising.symbol(), replaceLast(rising, flatRange).bars(), DATE).confirmed()).isFalse();
    }

    @Test void uncompletedDailyBarUnknownCalendarAndWrongSymbolNeverPass() {
        StockBars stock = population(1, "甲", 0, up(), "1.2").get(0);
        TradingClockService morning = new TradingClockService(Clock.fixed(Instant.parse("2026-09-07T02:00:00Z"), TradingClockService.CHINA_MARKET_ZONE));
        assertThat(new FiveDayContextEngine(morning).turning(stock.symbol(), stock.bars(), DATE).confirmed()).isFalse();
        assertThat(engine.turning("600999", stock.bars(), DATE).confirmed()).isFalse();
        assertThat(engine.returnPercent(stock.symbol(), stock.bars(), DATE, 0)).isNull();
        assertThat(engine.returnPercent(stock.symbol(), stock.bars(), LocalDate.of(2027, 9, 7), 5)).isNull();
        Context early = engine.analyze(DATE, Instant.parse("2026-09-07T02:00:00Z"), population(200, "甲", 0, up(), "1.2"), fourSectors());
        assertThat(early.market().state()).isEqualTo("UNKNOWN");
        assertThat(early.sectors()).allMatch(s -> s.state().equals("UNKNOWN"));
    }

    private Market market(int up, int flat, int down, int severe) {
        List<StockBars> stocks = new ArrayList<>();
        stocks.addAll(population(up, "行业", 0, new int[]{100, 100, 100, 100, 101}, "1"));
        stocks.addAll(population(flat, "行业", up, flat(), "1"));
        stocks.addAll(population(down, "行业", up + flat, new int[]{100, 100, 100, 100, 99}, "1"));
        stocks.addAll(population(severe, "行业", up + flat + down, new int[]{100, 100, 100, 100, 93}, "1"));
        return analyze(stocks, List.of()).market();
    }

    private Context analyze(List<StockBars> market, List<StockBars> scope) { return engine.analyze(DATE, AS_OF, market, scope); }
    private Sector sector(List<StockBars> scope, String industry) {
        List<Sector> sectors = analyze(List.of(), scope).sectors();
        assertThat(sectors).anyMatch(s -> s.industry().equals(industry));
        return sectors.stream().filter(s -> s.industry().equals(industry)).findFirst().orElseThrow();
    }
    private List<StockBars> fourSectors() {
        List<StockBars> result = population(5, "甲", 0, up(), "1.2");
        result.addAll(population(5, "乙", 10, flat(), "1.2"));
        result.addAll(population(5, "丙", 20, flat(), "1.2"));
        result.addAll(population(5, "丁", 30, flat(), "1.2"));
        return result;
    }
    private List<StockBars> population(int count, String industry, int firstSymbol, int[] recent, String volumeRatio) {
        List<StockBars> stocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String symbol = symbol(firstSymbol + i);
            List<EastMoneyKLine> bars = new ArrayList<>();
            LocalDate cursor = DATE;
            for (int day = 24; day >= 0; day--) {
                BigDecimal close = day < 20 ? d("100") : BigDecimal.valueOf(recent[day - 20]);
                BigDecimal volume = day < 20 ? d("100") : d("100").multiply(d(volumeRatio));
                bars.add(0, bar(symbol, cursor, close, volume));
                cursor = clock.previousTradingDay(cursor);
            }
            stocks.add(new StockBars(symbol, "示例" + i, industry, bars));
        }
        return stocks;
    }
    private StockBars replaceLast(StockBars stock, EastMoneyKLine bar) {
        List<EastMoneyKLine> bars = new ArrayList<>(stock.bars());
        bars.set(24, bar);
        return new StockBars(stock.symbol(), stock.name(), stock.industry(), bars);
    }
    private EastMoneyKLine bar(String symbol, LocalDate date, BigDecimal close, BigDecimal volume) {
        return new EastMoneyKLine(symbol, date, close.subtract(d("0.2")), close, close.add(d("0.4")), close.subtract(d("1")), volume, null);
    }
    private static int[] up() { return new int[]{101, 102, 103, 104, 105}; }
    private static int[] flat() { return new int[]{100, 100, 100, 100, 100}; }
    private static String symbol(int index) { return Integer.toString(600000 + index); }
    private static BigDecimal d(String value) { return new BigDecimal(value); }
}
