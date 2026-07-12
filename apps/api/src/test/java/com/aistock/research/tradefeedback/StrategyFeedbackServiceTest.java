package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyFeedbackServiceTest {

    private final TradeOutcomeRepository outcomes = mock(TradeOutcomeRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private StrategyFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new StrategyFeedbackService(outcomes, fills);
    }

    @Test
    void enforcesFourFiveAndTwentySampleGatesAndPositiveClamp() {
        List<MaturedRecommendationRow> rows = new ArrayList<>();
        rows.addAll(rows("FOUR", "v1", 4, decimal("1.00")));
        rows.addAll(rows("FIVE", "v1", 5, decimal("1.00")));
        rows.addAll(rows("TWENTY", "v1", 20, decimal("12.00")));
        when(outcomes.findMaturedRecommendationT20()).thenReturn(rows);
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of());

        List<StrategyFeedbackSummary> summaries = service.summaries();
        List<StrategyFeedbackSummary> promptContext = service.promptContext("002714");

        assertThat(summary(summaries, "FOUR", "v1").promptEligible()).isFalse();
        assertThat(summary(summaries, "FOUR", "v1").reliabilityAdjustment()).isNull();
        assertThat(summary(summaries, "FIVE", "v1").promptEligible()).isTrue();
        assertThat(summary(summaries, "FIVE", "v1").adjustmentEligible()).isFalse();
        assertThat(summary(summaries, "TWENTY", "v1").adjustmentEligible()).isTrue();
        assertThat(summary(summaries, "TWENTY", "v1").reliabilityAdjustment())
                .isEqualByComparingTo("5.00");
        assertThat(promptContext).extracting(StrategyFeedbackSummary::sourceModule)
                .containsExactly("FIVE", "TWENTY");
    }

    @Test
    void clampsNegativeReliabilityAdjustmentAtMinusFive() {
        when(outcomes.findMaturedRecommendationT20()).thenReturn(rows("WEAK", "v1", 20, decimal("-8.00")));
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of());

        StrategyFeedbackSummary summary = service.summaries().get(0);

        assertThat(summary.positiveRate()).isEqualByComparingTo("0.0000");
        assertThat(summary.reliabilityAdjustment()).isEqualByComparingTo("-5.00");
    }

    @Test
    void calculatesOddAndEvenMediansDeterministically() {
        List<MaturedRecommendationRow> rows = List.of(
                row("odd-1", "ODD", "v1", "1.00", "2.00", "-1.00", "2026-06-01T15:30:00Z"),
                row("odd-2", "ODD", "v1", "9.00", "4.00", "-3.00", "2026-06-02T15:30:00Z"),
                row("odd-3", "ODD", "v1", "3.00", "6.00", "-5.00", "2026-06-03T15:30:00Z"),
                row("even-1", "EVEN", "v1", "1.00", "1.00", "-1.00", "2026-06-01T15:30:00Z"),
                row("even-2", "EVEN", "v1", "3.00", "3.00", "-3.00", "2026-06-02T15:30:00Z"),
                row("even-3", "EVEN", "v1", "7.00", "7.00", "-7.00", "2026-06-03T15:30:00Z"),
                row("even-4", "EVEN", "v1", "9.00", "9.00", "-9.00", "2026-06-04T15:30:00Z")
        );
        when(outcomes.findMaturedRecommendationT20()).thenReturn(rows);
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of());

        List<StrategyFeedbackSummary> summaries = service.summaries();

        assertThat(summary(summaries, "ODD", "v1").medianReturn()).isEqualByComparingTo("3.0000");
        assertThat(summary(summaries, "EVEN", "v1").medianReturn()).isEqualByComparingTo("5.0000");
        assertThat(summary(summaries, "ODD", "v1").averageRunup()).isEqualByComparingTo("4.0000");
        assertThat(summary(summaries, "ODD", "v1").averageDrawdown()).isEqualByComparingTo("-3.0000");
    }

    @Test
    void excludesNullReturnRowsButMissingBuyOnlyReducesDeviationDenominator() {
        MaturedRecommendationRow includedWithoutBuy = row(
                "case-no-buy", "MODULE", "v1", "2.00", "5.00", "-2.00", "2026-06-01T16:30:00Z");
        MaturedRecommendationRow includedWithBuy = row(
                "case-buy", "MODULE", "v1", "-1.00", "4.00", "-3.00", "2026-06-02T16:30:00Z");
        MaturedRecommendationRow nullReturn = new MaturedRecommendationRow(
                "case-null", "MODULE", "v1", decimal("100.00"),
                Instant.parse("2026-06-03T16:30:00Z"), null, decimal("8.00"), decimal("-4.00"));
        when(outcomes.findMaturedRecommendationT20())
                .thenReturn(List.of(includedWithoutBuy, includedWithBuy, nullReturn));
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of(fill("buy-1", "case-buy", "BUY", "2026-06-03T01:00:00Z", "110.00",
                        "2026-06-03T01:01:00Z")));

        StrategyFeedbackSummary summary = service.summaries().get(0);

        assertThat(summary.sampleCount()).isEqualTo(2);
        assertThat(summary.executionDeviationSampleCount()).isEqualTo(1);
        assertThat(summary.averageExecutionDeviation()).isEqualByComparingTo("10.0000");
        assertThat(summary.sampleStart()).isEqualTo(LocalDate.parse("2026-06-02"));
        assertThat(summary.sampleEnd()).isEqualTo(LocalDate.parse("2026-06-03"));
    }

    @Test
    void selectsFirstBuyByExecutedAtThenCreatedAtThenFillId() {
        when(outcomes.findMaturedRecommendationT20()).thenReturn(List.of(
                row("case-fill-id", "MODULE", "v1", "2.00", "4.00", "-2.00", "2026-06-01T00:00:00Z"),
                row("case-created-at", "MODULE", "v1", "2.00", "4.00", "-2.00", "2026-06-01T00:00:00Z"),
                row("case-executed-at", "MODULE", "v1", "2.00", "4.00", "-2.00", "2026-06-01T00:00:00Z")));
        Instant executedAt = Instant.parse("2026-06-02T01:00:00Z");
        Instant createdAt = Instant.parse("2026-06-02T01:01:00Z");
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of(
                        TradeFillEntity.create("z-fill", "case-fill-id", "BUY", executedAt,
                                decimal("130.00"), 100, createdAt),
                        TradeFillEntity.create("a-fill", "case-fill-id", "BUY", executedAt,
                                decimal("105.00"), 100, createdAt),
                        TradeFillEntity.create("later-created", "case-created-at", "BUY", executedAt,
                                decimal("130.00"), 100, createdAt),
                        TradeFillEntity.create("earlier-created", "case-created-at", "BUY", executedAt,
                                decimal("102.00"), 100, createdAt.minusSeconds(1)),
                        TradeFillEntity.create("later-executed", "case-executed-at", "BUY", executedAt,
                                decimal("90.00"), 100, createdAt.minusSeconds(10)),
                        TradeFillEntity.create("earlier-executed", "case-executed-at", "BUY",
                                executedAt.minusSeconds(1), decimal("110.00"), 100, createdAt),
                        TradeFillEntity.create("sell", "case-fill-id", "SELL", executedAt.minusSeconds(10),
                                decimal("50.00"), 100, createdAt.minusSeconds(10))
                ));

        StrategyFeedbackSummary summary = service.summaries().get(0);

        assertThat(summary.averageExecutionDeviation()).isEqualByComparingTo("5.6667");
    }

    @Test
    void returnsExactCohortsInStableSourceModuleAndRuleVersionOrder() {
        when(outcomes.findMaturedRecommendationT20()).thenReturn(List.of(
                row("z-2", "ZETA", "v2", "1", "1", "-1", "2026-06-01T00:00:00Z"),
                row("a-2", "ALPHA", "v2", "1", "1", "-1", "2026-06-01T00:00:00Z"),
                row("a-1", "ALPHA", "v1", "1", "1", "-1", "2026-06-01T00:00:00Z")
        ));
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of());

        assertThat(service.summaries())
                .extracting(summary -> summary.sourceModule() + "/" + summary.ruleVersion())
                .containsExactly("ALPHA/v1", "ALPHA/v2", "ZETA/v2");
    }

    @Test
    void usesOneBulkOutcomeQueryAndOneBulkBuyQueryPerAggregation() {
        when(outcomes.findMaturedRecommendationT20()).thenReturn(rows("MODULE", "v1", 5, decimal("1.00")));
        when(fills.findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY")))
                .thenReturn(List.of());

        service.summaries();

        verify(outcomes).findMaturedRecommendationT20();
        verify(fills).findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY"));
    }

    @Test
    void skipsBulkFillQueryWhenNoEligibleRowsExist() {
        when(outcomes.findMaturedRecommendationT20()).thenReturn(List.of());

        assertThat(service.summaries()).isEmpty();

        verify(fills, never())
                .findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(anyCollection(), eq("BUY"));
    }

    private List<MaturedRecommendationRow> rows(
            String sourceModule,
            String ruleVersion,
            int count,
            BigDecimal returnPct
    ) {
        List<MaturedRecommendationRow> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(new MaturedRecommendationRow(
                    sourceModule + "-" + ruleVersion + "-" + index,
                    sourceModule,
                    ruleVersion,
                    decimal("100.00"),
                    Instant.parse("2026-06-01T00:00:00Z").plusSeconds(index * 86_400L),
                    returnPct,
                    decimal("15.00"),
                    decimal("-6.00")
            ));
        }
        return rows;
    }

    private MaturedRecommendationRow row(
            String caseId,
            String sourceModule,
            String ruleVersion,
            String returnPct,
            String maxRunupPct,
            String maxDrawdownPct,
            String recommendedAt
    ) {
        return new MaturedRecommendationRow(
                caseId,
                sourceModule,
                ruleVersion,
                decimal("100.00"),
                Instant.parse(recommendedAt),
                decimal(returnPct),
                decimal(maxRunupPct),
                decimal(maxDrawdownPct)
        );
    }

    private TradeFillEntity fill(
            String fillId,
            String caseId,
            String side,
            String executedAt,
            String price,
            String createdAt
    ) {
        return TradeFillEntity.create(
                fillId, caseId, side, Instant.parse(executedAt), decimal(price), 100, Instant.parse(createdAt));
    }

    private StrategyFeedbackSummary summary(
            List<StrategyFeedbackSummary> summaries,
            String sourceModule,
            String ruleVersion
    ) {
        return summaries.stream()
                .filter(summary -> sourceModule.equals(summary.sourceModule())
                        && ruleVersion.equals(summary.ruleVersion()))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
