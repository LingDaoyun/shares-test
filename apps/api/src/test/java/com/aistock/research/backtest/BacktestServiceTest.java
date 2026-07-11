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
}
