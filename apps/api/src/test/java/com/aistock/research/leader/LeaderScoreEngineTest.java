package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardBar;
import com.aistock.research.firstboard.FirstBoardBoard;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderScoreEngineTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-09-03");
    private final LeaderScoreEngine scoreEngine = new LeaderScoreEngine();

    @Test
    void gradeAndRiskCapHonorExactBoundaries() {
        assertThat(scoreEngine.grade(bd("80.00"))).isEqualTo(PotentialLeaderSnapshot.Grade.A);
        assertThat(scoreEngine.grade(bd("79.99"))).isEqualTo(PotentialLeaderSnapshot.Grade.B);
        assertThat(scoreEngine.grade(bd("65.00"))).isEqualTo(PotentialLeaderSnapshot.Grade.B);
        assertThat(scoreEngine.grade(bd("64.99"))).isEqualTo(PotentialLeaderSnapshot.Grade.C);
        assertThat(scoreEngine.grade(bd("54.99"))).isNull();
        assertThat(scoreEngine.capRisk(bd("37"))).isEqualByComparingTo("30");
    }

    @Test
    void ignoresDragonTigerAdjustmentForPotentialLane() {
        LeaderCandidateEngine.ScorableCandidate row = candidate(PotentialLeaderSnapshot.Lane.POTENTIAL);

        BigDecimal withoutAdjustment = scoreEngine.rankPotential(
                List.of(row), List.of(row.direction()), Map.of(),
                Map.of(row.quote().symbol(), evidence("0"))).get(0).score();
        BigDecimal withAdjustment = scoreEngine.rankPotential(
                List.of(row), List.of(row.direction()), Map.of(),
                Map.of(row.quote().symbol(), evidence("10"))).get(0).score();

        assertThat(withAdjustment).isEqualByComparingTo(withoutAdjustment);
    }

    @Test
    void clampsCompetitionDragonTigerAdjustmentToMinusFiveAndPlusTen() {
        LeaderCandidateEngine.ScorableCandidate row = candidate(PotentialLeaderSnapshot.Lane.COMPETITION);
        PotentialLeaderSnapshot.MarketSummary market = new PotentialLeaderSnapshot.MarketSummary(
                60, 10, 5, bd("85"), bd("30"), "情绪强势");

        BigDecimal minusNine = competitionScore(row, market, "-9");
        BigDecimal minusFive = competitionScore(row, market, "-5");
        BigDecimal plusSeventeen = competitionScore(row, market, "17");
        BigDecimal plusTen = competitionScore(row, market, "10");

        assertThat(minusNine).isEqualByComparingTo(minusFive);
        assertThat(plusSeventeen).isEqualByComparingTo(plusTen);
    }

    private BigDecimal competitionScore(
            LeaderCandidateEngine.ScorableCandidate row,
            PotentialLeaderSnapshot.MarketSummary market,
            String adjustment
    ) {
        return scoreEngine.rankCompetition(
                List.of(row), List.of(row.direction()), market,
                Map.of(row.quote().symbol(), evidence(adjustment)), Map.of()).get(0).score();
    }

    private PotentialLeaderSnapshot.DragonTigerEvidence evidence(String adjustment) {
        return new PotentialLeaderSnapshot.DragonTigerEvidence(
                PotentialLeaderSnapshot.DisclosureStatus.DISCLOSED, TRADE_DATE, bd(adjustment),
                List.of(), List.of());
    }

    private LeaderCandidateEngine.ScorableCandidate candidate(PotentialLeaderSnapshot.Lane lane) {
        Instant quoteTime = Instant.parse("2026-09-03T06:30:00Z");
        EastMoneyQuote quote = new EastMoneyQuote(
                "600000", "测试股份", "沪深", "半导体",
                bd("10.80"), bd("8.00"), bd("8.00"), bd("1000000"), bd("180000000"),
                null, null, null, "test", "https://example.test", quoteTime, TRADE_DATE, quoteTime,
                bd("12000000000"), bd("10.10"), bd("10.90"), bd("10.00"), bd("1.80"),
                bd("8000000000"));
        List<FirstBoardBar> bars = java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> new FirstBoardBar(
                        TRADE_DATE.minusDays(60L - index), bd("9.50"), bd("10.00"),
                        bd("10.10"), bd("9.40"), bd("100000")))
                .toList();
        FirstBoardLeaderBaselineStock baseline = new FirstBoardLeaderBaselineStock(
                "600000", "测试股份", "半导体", FirstBoardBoard.MAIN, TRADE_DATE.minusDays(1),
                bd("10.00"), bd("9.80"), bd("9.50"), bd("0.03"), bd("58"), bd("0.12"),
                5, bd("1.20"), bd("1.80"), bd("0.10"), bd("0.08"), false, bars);
        PotentialLeaderSnapshot.Direction direction = new PotentialLeaderSnapshot.Direction(
                "半导体", 1, bd("82"), bd("70"), bd("5"), 3, 1, 3,
                bd("500000000"), List.of("600000"));
        LeaderCandidateEngine.BoardState boardState = new LeaderCandidateEngine.BoardState(
                lane == PotentialLeaderSnapshot.Lane.COMPETITION, 2,
                LocalTime.of(9, 45), LocalTime.of(9, 45), 0, bd("20000000"));
        return new LeaderCandidateEngine.ScorableCandidate(
                lane, quote, baseline, direction, boardState,
                bd("0.80"), bd("0.80"), bd("0.70"), bd("0.60"), bd("0.70"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
