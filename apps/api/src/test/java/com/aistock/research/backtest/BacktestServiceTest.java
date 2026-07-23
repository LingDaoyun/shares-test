package com.aistock.research.backtest;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestServiceTest {

    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final BacktestService service = new BacktestService(eastMoneyClient);

    @Test
    void shouldNotInjectDefaultSymbolsWhenNoBacktestUniverseIsProvided() {
        BacktestReport report = service.rightSideBacktest(null, 400, 20, null, null, null, null, null);

        assertThat(report.symbols()).isEmpty();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void shouldBacktestRightSideSignalWithNextDayEntry() {
        eastMoneyClient.klines.put("600001", rightSideRows("600001", false));

        BacktestReport report = service.rightSideBacktest("600001", 400, 20, null, null, null, null, null);

        assertThat(report.scope()).contains("右侧");
        assertThat(report.summary().tradeCount()).isGreaterThanOrEqualTo(1);
        BacktestTrade firstTrade = report.results().get(0).trades().get(0);
        assertThat(firstTrade.entryDate()).isAfter(firstTrade.signalDate());
        assertThat(firstTrade.returnPercent()).isGreaterThan(BigDecimal.ZERO);
        assertThat(firstTrade.signalEvidence()).anySatisfy(item -> assertThat(item).contains("20日量比"));
    }

    @Test
    void shouldUseConservativeStopLossWhenLowBreaksStopPrice() {
        eastMoneyClient.klines.put("600002", rightSideRows("600002", true));

        BacktestReport report = service.rightSideBacktest("600002", 400, 20, null, null, null, null, null);

        BacktestTrade firstTrade = report.results().get(0).trades().get(0);
        assertThat(firstTrade.exitReason()).isEqualTo("STOP_LOSS");
        assertThat(firstTrade.returnPercent()).isLessThan(BigDecimal.ZERO);
    }

    @Test
    void simulatesFirstAndSecondTargetsWithoutHoldingPastT2() {
        eastMoneyClient.klines.put("600101", overnightRows("600101",
                bar("10.45", "10.50", "10.75", "10.30"),
                bar("10.55", "10.60", "10.70", "10.45")));
        eastMoneyClient.klines.put("600102", overnightRows("600102",
                bar("10.45", "10.50", "10.60", "10.30"),
                bar("10.55", "10.80", "11.00", "10.45")));

        OvernightBacktestReport report = overnightBacktest("600101,600102");

        assertThat(report.trades())
                .extracting(OvernightBacktestTrade::exitReason)
                .containsExactlyInAnyOrder("FIRST_TARGET", "SECOND_TARGET");
        assertThat(report.trades())
                .filteredOn(trade -> !"LIMIT_DOWN_DELAYED".equals(trade.exitReason()))
                .allSatisfy(trade -> assertThat(trade.holdingTradingDays()).isBetween(1, 2));
        assertThat(report.summary().firstTargetRatePercent()).isEqualByComparingTo("50.00");
        assertThat(report.summary().secondTargetRatePercent()).isEqualByComparingTo("50.00");
    }

    @Test
    void resolvesAdverseT1PriceBeforeProfitTargets() {
        eastMoneyClient.klines.put("600103", overnightRows("600103",
                bar("10.40", "10.50", "10.95", "9.90"),
                bar("10.50", "10.60", "10.80", "10.40")));

        OvernightBacktestTrade trade = onlyOvernightTrade("600103");

        assertThat(trade.exitReason()).isEqualTo("HARD_STOP");
        assertThat(trade.holdingTradingDays()).isEqualTo(1);
        assertThat(trade.netReturnPercent()).isNegative();
    }

    @Test
    void timeExitsOnT1UnlessTheProfitableTrendExtensionGatePasses() {
        eastMoneyClient.klines.put("600104", overnightRows("600104",
                bar("10.35", "10.30", "10.55", "10.20"),
                bar("10.40", "10.45", "10.60", "10.30")));
        eastMoneyClient.klines.put("600105", overnightRows("600105",
                bar("10.45", "10.50", "10.60", "10.30"),
                bar("10.52", "10.55", "10.65", "10.40")));

        OvernightBacktestReport report = overnightBacktest("600104,600105");

        assertThat(report.trades())
                .extracting(OvernightBacktestTrade::exitReason)
                .containsExactlyInAnyOrder("T1_TIME_EXIT", "T2_TIME_EXIT");
        assertThat(report.trades())
                .filteredOn(trade -> "T1_TIME_EXIT".equals(trade.exitReason()))
                .allSatisfy(trade -> assertThat(trade.holdingTradingDays()).isEqualTo(1));
        assertThat(report.trades())
                .filteredOn(trade -> "T2_TIME_EXIT".equals(trade.exitReason()))
                .allSatisfy(trade -> assertThat(trade.holdingTradingDays()).isEqualTo(2));
        assertThat(report.summary().timeStopRatePercent()).isEqualByComparingTo("100.00");
    }

    @Test
    void recordsNextDayGapDownAndAllTradingCostsInNetReturn() {
        eastMoneyClient.klines.put("600106", overnightRows("600106",
                bar("10.10", "10.30", "10.50", "10.08"),
                bar("10.35", "10.40", "10.55", "10.25")));

        OvernightBacktestReport report = overnightBacktest("600106");
        OvernightBacktestTrade trade = report.trades().get(0);

        assertThat(trade.gapPercent()).isNegative();
        assertThat(report.summary().gapDownRatePercent()).isEqualByComparingTo("100.00");
        assertThat(trade.commissionCostPercent()).isEqualByComparingTo("0.06");
        assertThat(trade.stampDutyCostPercent()).isEqualByComparingTo("0.05");
        assertThat(trade.slippageCostPercent()).isEqualByComparingTo("0.10");
        assertThat(trade.totalCostPercent()).isEqualByComparingTo("0.21");
        assertThat(trade.netReturnPercent()).isLessThan(
                percent(new BigDecimal("10.30").subtract(new BigDecimal("10.42")), new BigDecimal("10.42")));
    }

    @Test
    void rejectsAOnePriceLimitUpSignalDayEntry() {
        List<EastMoneyKLine> rows = overnightRows("600107",
                bar("11.20", "11.25", "11.30", "11.10"),
                bar("11.25", "11.30", "11.40", "11.15"));
        int signalIndex = rows.size() - 3;
        rows.set(signalIndex - 1, customKline("600107", rows.get(signalIndex - 1).tradeDate(),
                "10.20", "10.20", "12.00", "9.50", "100000"));
        rows.set(signalIndex, customKline("600107", rows.get(signalIndex).tradeDate(),
                "11.20", "11.20", "11.20", "11.20", "190000"));
        eastMoneyClient.klines.put("600107", rows);

        OvernightBacktestReport report = overnightBacktest("600107");

        assertThat(report.trades()).isEmpty();
        assertThat(report.summary().sampleCount()).isZero();
    }

    @Test
    void delaysAnUntradeableT2LimitDownUntilTheFirstExecutableDay() {
        List<EastMoneyKLine> rows = overnightRows("600108",
                bar("10.45", "10.50", "10.60", "10.30"),
                bar("9.45", "9.45", "9.45", "9.45"));
        rows.add(customKline("600108", rows.get(rows.size() - 1).tradeDate().plusDays(1),
                "9.30", "9.40", "9.55", "9.20", "130000"));
        eastMoneyClient.klines.put("600108", rows);

        OvernightBacktestTrade trade = onlyOvernightTrade("600108");

        assertThat(trade.exitReason()).isEqualTo("LIMIT_DOWN_DELAYED");
        assertThat(trade.holdingTradingDays()).isEqualTo(3);
        assertThat(trade.exitDate()).isAfter(trade.t2Date());
    }

    @Test
    void summarizesOvernightMetricsWithoutChangingTheLegacyTwentyDayContract() {
        eastMoneyClient.klines.put("600109", overnightRows("600109",
                bar("10.45", "10.50", "10.75", "10.30"),
                bar("10.55", "10.60", "10.70", "10.45")));

        OvernightBacktestReport report = overnightBacktest("600109");

        assertThat(report.summary().positiveRatePercent()).isNotNull();
        assertThat(report.summary().averageReturnPercent()).isNotNull();
        assertThat(report.summary().medianReturnPercent()).isNotNull();
        assertThat(report.summary().averageRunupPercent()).isNotNull();
        assertThat(report.summary().averageDrawdownPercent()).isNotNull();
        assertThat(report.summary().hardStopRatePercent()).isNotNull();
        assertThat(report.summary().sampleStart()).isEqualTo(report.trades().get(0).signalDate());
        assertThat(report.summary().sampleEnd()).isEqualTo(report.trades().get(0).signalDate());

        BacktestReport legacy = service.rightSideBacktest("600109", 400, 20, null, null, null, null, null);
        assertThat(legacy.ruleSet().holdingDays()).isEqualTo(20);
    }

    private OvernightBacktestReport overnightBacktest(String symbols) {
        return service.overnightBacktest(
                symbols,
                900,
                new BigDecimal("2.5"),
                new BigDecimal("4.5"),
                new BigDecimal("3.5"),
                2,
                new BigDecimal("0.03"),
                new BigDecimal("0.05"),
                new BigDecimal("0.05"),
                new BigDecimal("9.80")
        );
    }

    private OvernightBacktestTrade onlyOvernightTrade(String symbol) {
        OvernightBacktestReport report = overnightBacktest(symbol);
        assertThat(report.trades()).hasSize(1);
        return report.trades().get(0);
    }

    private List<EastMoneyKLine> overnightRows(
            String symbol,
            Bar t1,
            Bar t2
    ) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2025-01-01");
        for (int index = 0; index < 70; index++) {
            BigDecimal close = new BigDecimal("10.00").add(new BigDecimal(index % 2 == 0 ? "0.02" : "-0.02"));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        for (int index = 70; index < 75; index++) {
            BigDecimal close = new BigDecimal("9.92").add(new BigDecimal("0.02").multiply(BigDecimal.valueOf(index - 70)));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        rows.add(customKline(symbol, start.plusDays(75), "10.35", "10.42", "10.49", "10.30", "190000"));
        rows.add(customKline(symbol, start.plusDays(76), t1.open(), t1.close(), t1.high(), t1.low(), "130000"));
        rows.add(customKline(symbol, start.plusDays(77), t2.open(), t2.close(), t2.high(), t2.low(), "130000"));
        return rows;
    }

    private Bar bar(String open, String close, String high, String low) {
        return new Bar(open, close, high, low);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 6, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private List<EastMoneyKLine> rightSideRows(String symbol, boolean stopLossAfterEntry) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2025-01-01");
        for (int index = 0; index < 70; index++) {
            BigDecimal close = new BigDecimal("10.00").add(new BigDecimal(index % 2 == 0 ? "0.02" : "-0.02"));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        for (int index = 70; index < 75; index++) {
            BigDecimal close = new BigDecimal("9.92").add(new BigDecimal("0.02").multiply(BigDecimal.valueOf(index - 70)));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        rows.add(kline(symbol, start.plusDays(75), new BigDecimal("10.42"), "190000"));

        if (stopLossAfterEntry) {
            rows.add(customKline(symbol, start.plusDays(76), "10.45", "10.30", "10.55", "9.70", "150000"));
            rows.add(customKline(symbol, start.plusDays(77), "10.20", "10.10", "10.30", "10.00", "130000"));
            for (int index = 78; index < 105; index++) {
                rows.add(customKline(symbol, start.plusDays(index), "10.05", "10.08", "10.18", "9.98", "120000"));
            }
            return rows;
        }

        for (int index = 76; index < 105; index++) {
            BigDecimal close = new BigDecimal("10.45").add(new BigDecimal("0.035").multiply(BigDecimal.valueOf(index - 76)));
            rows.add(customKline(symbol, start.plusDays(index), close.subtract(new BigDecimal("0.03")).toPlainString(),
                    close.toPlainString(), close.add(new BigDecimal("0.08")).toPlainString(), close.subtract(new BigDecimal("0.10")).toPlainString(), "130000"));
        }
        return rows;
    }

    private EastMoneyKLine kline(String symbol, LocalDate date, BigDecimal close, String volume) {
        return customKline(symbol, date, close.subtract(new BigDecimal("0.03")).toPlainString(), close.toPlainString(),
                close.add(new BigDecimal("0.07")).toPlainString(), close.subtract(new BigDecimal("0.08")).toPlainString(), volume);
    }

    private EastMoneyKLine customKline(String symbol, LocalDate date, String open, String close, String high, String low, String volume) {
        return new EastMoneyKLine(
                symbol,
                date,
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(volume),
                null
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private final Map<String, List<EastMoneyKLine>> klines = new HashMap<>();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return klines.getOrDefault(symbol, List.of());
        }
    }

    private record Bar(String open, String close, String high, String low) {
    }
}
