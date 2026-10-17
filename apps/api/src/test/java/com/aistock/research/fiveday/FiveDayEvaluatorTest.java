package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardBoard;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FiveDayEvaluatorTest {
    private final TradingClockService clock = new TradingClockService();
    private final FiveDayEvaluator evaluator = new FiveDayEvaluator(clock);
    private final LocalDate date = LocalDate.of(2026, 9, 7);

    @Test void missingFlowRemainsAQuantifiedControlAndNeverPassesTheFundCondition() {
        var result = evaluator.evaluate(stock(), bars(), List.of(), date, FiveDayModels.Rules.defaults(), List.of());
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().turnoverRatio()).isEqualByComparingTo("1.5");
        assertThat(result.candidate().flowDataComplete()).isFalse();
        assertThat(result.candidate().matchesFundFlow()).isFalse();
        assertThat(result.candidate().plannedExitDate()).isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test void aMissingMarketDayCannotBeReplacedWithAnOlderBar() {
        List<EastMoneyKLine> source = new ArrayList<>(bars());
        EastMoneyKLine first = source.get(0);
        source.remove(22);
        source.add(0, new EastMoneyKLine(first.symbol(), clock.previousTradingDay(first.tradeDate()),
                first.open(), first.close(), first.high(), first.low(), first.volume(), first.amount(), first.turnoverRate()));
        assertThat(evaluator.evaluate(stock(), source, List.of(), date,
                FiveDayModels.Rules.defaults(), List.of()).candidate()).isNull();
    }

    @Test void staleOrFutureFlowDoesNotSatisfyTheFiveDatedObservations() {
        List<EastMoneyFundFlowSnapshot> flow = List.of(flow(date.plusDays(1)), flow(date.minusDays(20)));
        var candidate = evaluator.evaluate(stock(), bars(), flow, date,
                FiveDayModels.Rules.defaults(), List.of()).candidate();
        assertThat(candidate.flowDataComplete()).isFalse();
        assertThat(candidate.mainNetInflowRatio()).isNull();
    }

    @Test void flowUsesSummedAmountsAndAtLeastThreePositiveDays() {
        List<EastMoneyKLine> source = new ArrayList<>(bars());
        List<EastMoneyFundFlowSnapshot> flows = new ArrayList<>();
        String[] inflows = {"2000000", "-1000000", "2000000", "-1000000", "3000000"};
        for (int i = 20; i < 25; i++) {
            EastMoneyKLine row = source.get(i);
            source.set(i, new EastMoneyKLine(row.symbol(), row.tradeDate(), row.open(), row.close(), row.high(), row.low(),
                    row.volume(), d(i == 24 ? "100000000" : "50000000"), row.turnoverRate()));
            flows.add(flow(row.tradeDate(), inflows[i - 20]));
        }
        var candidate = evaluator.evaluate(stock(), source, flows, date,
                FiveDayModels.Rules.defaults(), List.of()).candidate();
        assertThat(candidate.flowDataComplete()).isTrue();
        assertThat(candidate.matchesFundFlow()).isTrue();
        assertThat(candidate.positiveFlowDays()).isEqualTo(3);
        assertThat(candidate.mainNetInflow()).isEqualByComparingTo("5000000");
        assertThat(candidate.mainNetInflowRatio()).isEqualByComparingTo("1.6667");
    }

    @Test void realZeroFlowIsCompleteButDoesNotPassTheFundCondition() {
        List<EastMoneyFundFlowSnapshot> flows = bars().subList(20, 25).stream()
                .map(row -> flow(row.tradeDate(), "0")).toList();
        var candidate = evaluator.evaluate(stock(), bars(), flows, date,
                FiveDayModels.Rules.defaults(), List.of()).candidate();
        assertThat(candidate.flowDataComplete()).isTrue();
        assertThat(candidate.matchesFundFlow()).isFalse();
        assertThat(candidate.mainNetInflow()).isZero();
        assertThat(candidate.mainNetInflowRatio()).isZero();
    }

    @Test void nullMainFlowIsMissingAndNeverReplacedByZero() {
        List<EastMoneyFundFlowSnapshot> flows = new ArrayList<>(bars().subList(20, 25).stream()
                .map(row -> flow(row.tradeDate())).toList());
        flows.set(4, flow(date, null));
        var candidate = evaluator.evaluate(stock(), bars(), flows, date,
                FiveDayModels.Rules.defaults(), List.of()).candidate();
        assertThat(candidate.flowDataComplete()).isFalse();
        assertThat(candidate.matchesFundFlow()).isFalse();
        assertThat(candidate.mainNetInflow()).isNull();
        assertThat(candidate.mainNetInflowRatio()).isNull();
    }

    private List<EastMoneyKLine> bars() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = date;
        for (int i = 0; i < 25; i++) { dates.add(0, cursor); cursor = clock.previousTradingDay(cursor); }
        List<EastMoneyKLine> result = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) result.add(new EastMoneyKLine("600001", dates.get(i),
                d("10"), d("10"), d("10.2"), d("9.8"), d("6000000"), d("60000000"), i < 20 ? d("2") : d("3")));
        return result;
    }
    private FirstBoardLeaderBaselineStock stock() {
        return new FirstBoardLeaderBaselineStock("600001", "示例公司", "示例行业", FirstBoardBoard.MAIN,
                date, null, null, null, null, null, null, 0, null, null, null, null, false, List.of());
    }
    private EastMoneyFundFlowSnapshot flow(LocalDate day) {
        return flow(day, "1000000");
    }
    private EastMoneyFundFlowSnapshot flow(LocalDate day, String mainNetInflow) {
        return new EastMoneyFundFlowSnapshot("600001", null, mainNetInflow == null ? null : d(mainNetInflow), null, null, null, null,
                null, null, null, null, null, "fixture", "fixture", null, day, null);
    }
    private BigDecimal d(String v) { return new BigDecimal(v); }
}
