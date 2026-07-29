package com.aistock.research.longterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.valuation.ValuationContext;
import com.aistock.research.valuation.ValuationContextState;
import com.aistock.research.valuation.ValuationModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermInvestmentAssessmentServiceTest {

    private final LongTermInvestmentAssessmentService service = new LongTermInvestmentAssessmentService();

    @Test
    void keepsStandardCompanyResearchableWhenOneOfFiveYearsFallsBelowFifteenPercentRoe() {
        LongTermInvestmentAssessment assessment = service.assess(input(
                "600987",
                "纺织制造",
                "7.20",
                "11.80",
                "1.02",
                ValuationModel.STANDARD,
                List.of(
                        annual(2025, "0.16", "0.78", "0.31", "0.08", "0.11", "0.76", "3.10"),
                        annual(2024, "0.14", "0.70", "0.30", "0.06", "0.08", "0.69", "3.02"),
                        annual(2023, "0.13", "0.64", "0.29", "0.04", "0.05", "0.63", "2.95"),
                        annual(2022, "0.12", "0.59", "0.28", "0.02", "0.03", "0.58", "2.86"),
                        annual(2021, "0.09", "0.51", "0.27", "-0.01", "-0.03", "0.50", "2.72")
                )
        ));

        assertThat(assessment.modelCode()).isEqualTo("STANDARD");
        assertThat(assessment.status()).isNotEqualTo("BLOCKED");
        assertThat(assessment.financialQuality().sampleYears()).isEqualTo(5);
        assertThat(assessment.financialQuality().medianRoe()).isEqualByComparingTo("0.13");
        assertThat(assessment.financialQuality().roeReferenceMetYears()).isGreaterThanOrEqualTo(3);
        assertThat(assessment.valuation().metricCode()).isEqualTo("IMPLIED_GROWTH");
    }

    @Test
    void normalizesCyclicalEarningsInsteadOfUsingLatestPeAsFairValue() {
        LongTermInvestmentAssessment assessment = service.assess(input(
                "002714",
                "生猪养殖",
                "36.00",
                "-18.00",
                "2.40",
                ValuationModel.CYCLICAL,
                List.of(
                        annual(2025, "0.18", "4.80", "0.19", "0.12", "0.40", "3.20", "14.50"),
                        annual(2024, "-0.06", "1.20", "0.08", "-0.05", "-0.30", "-0.80", "12.80"),
                        annual(2023, "-0.12", "-0.40", "0.04", "-0.10", "-0.55", "-1.60", "11.90"),
                        annual(2022, "0.15", "3.90", "0.22", "0.18", "0.35", "2.80", "13.20"),
                        annual(2021, "0.24", "5.20", "0.28", "0.22", "0.48", "4.10", "12.40")
                )
        ));

        assertThat(assessment.modelCode()).isEqualTo("CYCLICAL");
        assertThat(assessment.valuation().normalizedEarningsUsed()).isTrue();
        assertThat(assessment.valuation().metricCode()).isEqualTo("IMPLIED_GROWTH");
        assertThat(assessment.valuation().targetMarginOfSafetyPercent()).isEqualByComparingTo("35");
        assertThat(assessment.valuation().baseValue()).isLessThan(new BigDecimal("36"));
        assertThat(assessment.valuation().evidence())
                .anyMatch(item -> item.contains("完整周期平均"));
        assertThat(assessment.dataGaps()).anyMatch(item -> item.contains("供需"));
    }

    @Test
    void routesBanksToPbRoeImpliedExpectationModel() {
        LongTermInvestmentAssessment assessment = service.assess(input(
                "600036",
                "银行",
                "36.83",
                "6.15",
                "0.83",
                ValuationModel.FINANCIAL,
                List.of(
                        annual(2025, "0.16", "5.10", null, "0.05", "0.06", "5.80", "43.20"),
                        annual(2024, "0.16", "4.90", null, "0.04", "0.05", "5.60", "41.10"),
                        annual(2023, "0.17", "4.70", null, "0.04", "0.06", "5.40", "39.20"),
                        annual(2022, "0.17", "4.40", null, "0.05", "0.07", "5.20", "37.50"),
                        annual(2021, "0.16", "4.10", null, "0.06", "0.08", "4.90", "35.80")
                )
        ));

        assertThat(assessment.modelCode()).isEqualTo("FINANCIAL");
        assertThat(assessment.valuation().metricCode()).isEqualTo("IMPLIED_ROE");
        assertThat(assessment.valuation().impliedExpectationPercent()).isBetween(
                new BigDecimal("8"),
                new BigDecimal("10")
        );
        assertThat(assessment.valuation().targetMarginOfSafetyPercent()).isEqualByComparingTo("20");
        assertThat(assessment.dataGaps()).anyMatch(item -> item.contains("不良率"));
    }

    @Test
    void fifteenPercentDeclineTriggersReviewButNeverAutomaticAddition() {
        LongTermInvestmentAssessment assessment = service.assess(input(
                "600987",
                "纺织制造",
                "7.20",
                "11.80",
                "1.02",
                ValuationModel.STANDARD,
                List.of(
                        annual(2025, "0.16", "0.78", "0.31", "0.08", "0.11", "0.76", "3.10"),
                        annual(2024, "0.14", "0.70", "0.30", "0.06", "0.08", "0.69", "3.02"),
                        annual(2023, "0.13", "0.64", "0.29", "0.04", "0.05", "0.63", "2.95")
                )
        ));

        assertThat(assessment.positionDiscipline().declineReviewTriggerPercent()).isEqualByComparingTo("15");
        assertThat(assessment.positionDiscipline().reviewTriggers())
                .anyMatch(item -> item.contains("下跌15%") && item.contains("复核"));
        assertThat(assessment.positionDiscipline().addConditions())
                .contains(
                        "原投资逻辑未被证伪",
                        "安全边际较上次决策扩大",
                        "经营现金流和偿债能力未恶化",
                        "没有新增治理或审计红旗"
                )
                .noneMatch(item -> item.contains("自动加仓"));
    }

    @Test
    void keepsOptimisticScenarioAboveBaseWhenEvidenceGrowthHitsBaseCap() {
        LongTermInvestmentAssessment assessment = service.assess(input(
                "300979",
                "纺织制造",
                "36.03",
                "12.00",
                "2.80",
                ValuationModel.STANDARD,
                List.of(
                        annual(2025, "0.24", "2.90", "0.27", "0.18", "0.20", "2.60", "13.20"),
                        annual(2024, "0.23", "2.70", "0.26", "0.16", "0.18", "2.40", "12.40"),
                        annual(2023, "0.22", "2.50", "0.25", "0.14", "0.16", "2.20", "11.60"),
                        annual(2022, "0.21", "2.30", "0.24", "0.13", "0.14", "2.00", "10.80"),
                        annual(2021, "0.20", "2.10", "0.23", "0.12", "0.12", "1.80", "10.00")
                )
        ));

        assertThat(assessment.valuation().evidenceExpectationPercent()).isEqualByComparingTo("12");
        assertThat(assessment.valuation().optimisticValue())
                .isGreaterThan(assessment.valuation().baseValue());
        assertThat(assessment.valuation().baseValue())
                .isGreaterThan(assessment.valuation().pessimisticValue());
    }

    @Test
    void requiresTargetMarginAndCriticalEvidenceBeforeBuildZoneReview() {
        List<EastMoneyAnnualIndicator> history = List.of(
                annual(2025, "0.18", "1.20", "0.31", "0.08", "0.11", "1.00", "5.10"),
                annual(2024, "0.17", "1.10", "0.30", "0.07", "0.09", "0.95", "4.90"),
                annual(2023, "0.16", "1.00", "0.29", "0.06", "0.08", "0.90", "4.70"),
                annual(2022, "0.15", "0.90", "0.28", "0.05", "0.07", "0.85", "4.50"),
                annual(2021, "0.14", "0.80", "0.27", "0.04", "0.06", "0.80", "4.30")
        );
        LongTermInvestmentAssessment probe = service.assess(input(
                "600001",
                "食品制造",
                "1.00",
                "12.00",
                "1.50",
                ValuationModel.STANDARD,
                history
        ));
        BigDecimal baseValue = probe.valuation().baseValue();
        LongTermInvestmentAssessment belowTargetMargin = service.assess(input(
                "600001",
                "食品制造",
                baseValue.multiply(new BigDecimal("0.90")).toPlainString(),
                "12.00",
                "1.50",
                ValuationModel.STANDARD,
                history
        ));
        LongTermInvestmentAssessment missingCriticalEvidence = service.assess(input(
                "600001",
                "食品制造",
                baseValue.multiply(new BigDecimal("0.50")).toPlainString(),
                "12.00",
                "1.50",
                ValuationModel.STANDARD,
                history
        ));
        assertThat(belowTargetMargin.valuation().discountToBasePercent())
                .isLessThan(belowTargetMargin.valuation().targetMarginOfSafetyPercent());
        assertThat(belowTargetMargin.status()).isEqualTo("WATCH");
        assertThat(missingCriticalEvidence.valuation().discountToBasePercent())
                .isGreaterThan(missingCriticalEvidence.valuation().targetMarginOfSafetyPercent());
        assertThat(missingCriticalEvidence.status()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(missingCriticalEvidence.dataGaps())
                .anyMatch(item -> item.contains("尚未接入自动复核调度"));
    }

    @Test
    void rejectsNonPositiveAndIncompleteCyclicalOwnerEarnings() {
        LongTermInvestmentAssessment nonPositive = service.assess(input(
                "002714",
                "生猪养殖",
                "30.00",
                "-10.00",
                "2.00",
                ValuationModel.CYCLICAL,
                List.of(
                        annual(2025, "-0.08", "-0.50", "0.08", "-0.08", "-0.20", "-1.00", "12.00"),
                        annual(2024, "0.04", "0.60", "0.12", "0.05", "0.10", "0.50", "11.80"),
                        annual(2023, "-0.06", "-0.40", "0.07", "-0.06", "-0.18", "-0.80", "11.50"),
                        annual(2022, "0.03", "0.40", "0.11", "0.03", "0.08", "0.30", "11.30"),
                        annual(2021, "-0.02", "-0.20", "0.09", "-0.02", "-0.06", "-0.20", "11.10")
                )
        ));
        LongTermInvestmentAssessment incomplete = service.assess(input(
                "002714",
                "生猪养殖",
                "30.00",
                "-10.00",
                "2.00",
                ValuationModel.CYCLICAL,
                List.of(
                        annual(2025, "0.18", "4.80", "0.19", "0.12", "0.40", "3.20", "14.50"),
                        annual(2024, "0.10", "2.20", "0.14", "0.06", "0.20", "1.40", "13.80"),
                        annual(2023, "0.08", "1.60", "0.12", "0.03", "0.10", "1.00", "13.20"),
                        annual(2022, "-0.05", null, "0.07", "-0.08", "-0.25", "-0.70", "12.60"),
                        annual(2021, "-0.10", null, "0.05", "-0.12", "-0.40", "-1.30", "12.00")
                )
        ));

        assertThat(nonPositive.valuation().baseValue()).isNull();
        assertThat(nonPositive.valuation().impliedExpectationPercent()).isNull();
        assertThat(nonPositive.valuation().dataGaps())
                .anyMatch(item -> item.contains("完整周期平均经营者收益不为正"));
        assertThat(incomplete.valuation().confidence()).isEqualTo("LOW");
        assertThat(incomplete.valuation().dataGaps())
                .anyMatch(item -> item.contains("完整周期平均仍不完整"));
    }

    private LongTermAssessmentInput input(
            String symbol,
            String industry,
            String price,
            String pe,
            String pb,
            ValuationModel model,
            List<EastMoneyAnnualIndicator> history
    ) {
        BigDecimal rawPe = decimal(pe);
        BigDecimal rawPb = decimal(pb);
        return new LongTermAssessmentInput(
                symbol,
                industry,
                decimal(price),
                new ValuationContext(
                        new BigDecimal("65"),
                        rawPe != null && rawPe.signum() <= 0 ? ValuationContextState.DISTORTED : ValuationContextState.FAIR,
                        model,
                        rawPe,
                        rawPb,
                        new BigDecimal("45"),
                        new BigDecimal("6"),
                        null,
                        null,
                        false,
                        List.of(),
                        List.of()
                ),
                history.isEmpty() ? null : history.get(0),
                history,
                new BigDecimal("20"),
                history.size() + 2,
                true,
                "纺织制造".equals(industry)
        );
    }

    private EastMoneyAnnualIndicator annual(
            int year,
            String roe,
            String operatingCashFlowPerShare,
            String grossMargin,
            String revenueGrowth,
            String netProfitGrowth,
            String eps,
            String bps
    ) {
        return new EastMoneyAnnualIndicator(
                "fixture",
                "fixture",
                year + "-12-31",
                year + "年 年报",
                decimal(roe),
                decimal(operatingCashFlowPerShare),
                decimal(grossMargin),
                decimal(revenueGrowth),
                decimal(netProfitGrowth),
                decimal(eps),
                decimal(bps),
                null,
                null,
                year >= 2023 ? "10派3元" : null,
                year >= 2023 ? new BigDecimal("0.03") : null
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
