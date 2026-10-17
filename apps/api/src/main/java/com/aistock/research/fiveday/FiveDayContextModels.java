package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class FiveDayContextModels {
    private FiveDayContextModels() { }

    public record StockBars(String symbol, String name, String industry, List<EastMoneyKLine> bars) {
        public StockBars { bars = safe(bars); }
    }

    public record Market(String state, int expectedCount, int coveredCount, BigDecimal coveragePercent,
                         BigDecimal advancingPercent, BigDecimal medianChangePercent,
                         BigDecimal severeDeclinePercent, List<String> reasons) {
        public Market { reasons = safe(reasons); }
    }

    public record Sector(String industry, String state, int rank, int memberCount, int coveredCount,
                         BigDecimal coveragePercent, BigDecimal return3Percent, BigDecimal return5Percent,
                         BigDecimal excess3Percent, BigDecimal excess5Percent, BigDecimal advancing5Percent,
                         BigDecimal volumeExpansionPercent, List<String> symbols, List<String> reasons) {
        public Sector { symbols = safe(symbols); reasons = safe(reasons); }
    }

    public record Turning(boolean confirmed, BigDecimal closeVsMa5Percent,
                          BigDecimal closePositionPercent, List<String> reasons) {
        public Turning { reasons = safe(reasons); }
    }

    public record Evidence(String kind, String title, String url, String source, String publishedAt,
                           Instant observedAt, String interpretation, String temporalStatus) { }

    public record PolicyBundle(String status, List<Evidence> evidence, List<String> notes) {
        public PolicyBundle { evidence = safe(evidence); notes = safe(notes); }
    }

    public record EventReview(String status, List<Evidence> evidence, List<String> notes) {
        public EventReview { evidence = safe(evidence); notes = safe(notes); }
    }

    public record CandidateAssessment(String symbol, String industry, String decision, Turning turning,
                                      EventReview events, List<Evidence> policies, List<String> reasons) {
        public CandidateAssessment { policies = safe(policies); reasons = safe(reasons); }
    }

    public record Context(Instant assessedAt, Market market, List<Sector> sectors,
                          List<CandidateAssessment> assessments, List<Evidence> policies,
                          String policyStatus, List<String> notes) {
        public Context {
            sectors = safe(sectors);
            assessments = safe(assessments);
            policies = safe(policies);
            notes = safe(notes);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
