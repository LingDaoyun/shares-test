package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Frozen, inspectable evidence; no score is presented as a probability. */
public final class FiveDayModels {
    private FiveDayModels() { }
    public static final String VERSION = "five-day-observation-v1";
    public static final String VERSION_V2 = "five-day-context-v2";

    public record Rules(int holdingDays, int rangeDays, int recentTurnoverDays,
                        int priorTurnoverDays, int flowDays, BigDecimal maxRangePercent,
                        BigDecimal maxAbsPriceChangePercent, BigDecimal minCenterChangePercent,
                        BigDecimal minTurnoverRatio, int minPositiveFlowDays,
                        BigDecimal minAverageAmount, BigDecimal maxEntryPremiumPercent,
                        BigDecimal maxInitialRiskPercent, BigDecimal stopBufferPercent,
                        BigDecimal roundTripCostBps, int maximumReviewed) {
        public static Rules defaults() {
            return new Rules(5, 10, 5, 20, 5, decimal("8"), decimal("3"),
                    decimal("-1"), decimal("1.2"), 3, decimal("50000000"),
                    decimal("2"), decimal("5"), decimal("0.5"), decimal("20"), 100);
        }
    }

    public record Candidate(String symbol, String name, String industry, String board,
                            BigDecimal signalClose, BigDecimal rangeHigh, BigDecimal rangeLow,
                            BigDecimal rangePercent, BigDecimal priceChangePercent,
                            BigDecimal centerChangePercent, BigDecimal recentTurnover,
                            BigDecimal priorTurnover, BigDecimal turnoverRatio,
                            BigDecimal averageAmount, BigDecimal mainNetInflow,
                            BigDecimal mainNetInflowRatio, int positiveFlowDays,
                            boolean flowDataComplete, boolean matchesFundFlow,
                            String flowSourceUrl, BigDecimal stopPrice, BigDecimal maxEntryPrice,
                            LocalDate entryDate, LocalDate plannedExitDate,
                            List<String> evidence, List<String> dataGaps,
                            List<EastMoneyKLine> bars) {
        public Candidate {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
            bars = bars == null ? List.of() : List.copyOf(bars);
        }
    }

    public record Coverage(int universeCount, int priceQualifiedCount, int reviewedCount,
                           int technicalQualifiedCount, int fundFlowQualifiedCount,
                           int missingDataCount, int unreviewedCount) { }

    public record Snapshot(String status, String ruleVersion, LocalDate signalDate,
                           Instant generatedAt, boolean forwardEligible,
                           boolean includeGrowth, boolean includeStar, String message,
                           Rules rules, Coverage coverage, List<Candidate> candidates,
                           List<String> dataGaps, FiveDayContextModels.Context context) {
        public Snapshot(String status, String ruleVersion, LocalDate signalDate, Instant generatedAt,
                        boolean forwardEligible, boolean includeGrowth, boolean includeStar, String message,
                        Rules rules, Coverage coverage, List<Candidate> candidates, List<String> dataGaps) {
            this(status, ruleVersion, signalDate, generatedAt, forwardEligible, includeGrowth, includeStar,
                    message, rules, coverage, candidates, dataGaps, null);
        }
        public Snapshot {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }

    public record Outcome(String fixedStatus, String riskStatus, LocalDate entryDate,
                          BigDecimal entryPrice, LocalDate plannedExitDate,
                          LocalDate fixedExitDate, BigDecimal fixedExitPrice,
                          BigDecimal fixedGrossReturnPercent, BigDecimal fixedNetReturnPercent,
                          LocalDate riskExitDate, BigDecimal riskExitPrice,
                          BigDecimal riskNetReturnPercent, BigDecimal maxAdverseExcursionPercent,
                          List<String> details) { }

    public record HistoryRow(String observationId, LocalDate signalDate, Instant capturedAt,
                             boolean forwardEligible, String symbol, String name,
                             boolean flowDataComplete, boolean matchesFundFlow, Outcome outcome, String decision) {
        public HistoryRow(String observationId, LocalDate signalDate, Instant capturedAt, boolean forwardEligible,
                          String symbol, String name, boolean flowDataComplete, boolean matchesFundFlow, Outcome outcome) {
            this(observationId, signalDate, capturedAt, forwardEligible, symbol, name, flowDataComplete,
                    matchesFundFlow, outcome, null);
        }
    }

    public record Cohort(String label, int samples, int completed, int unavailable,
                         BigDecimal meanFixedNetReturnPercent, BigDecimal profitablePercent,
                         BigDecimal meanRiskNetReturnPercent) { }

    public record History(List<HistoryRow> rows, List<Cohort> cohorts, List<String> notes) { }

    public record TradeSchedule(LocalDate entryDate, LocalDate plannedExitDate,
                                Integer holdingDay, String status, String detail) { }

    public record Attestation(String symbol, Instant recommendedAt, String attestationToken) { }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
