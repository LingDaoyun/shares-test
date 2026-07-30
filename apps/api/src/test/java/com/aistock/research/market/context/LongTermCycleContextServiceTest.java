package com.aistock.research.market.context;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermCycleContextServiceTest {

    private final LongTermCycleContextService service = new LongTermCycleContextService();

    @Test
    void keepsStrongCycleRecoveryProvisionalWithoutProductEvidence() {
        List<EastMoneyAnnualIndicator> financials = List.of(
                annual("2025-12-31", 0.16, 0.22, 0.12, 0.80),
                annual("2024-12-31", 0.09, 0.17, 0.01, 0.08),
                annual("2023-12-31", 0.04, 0.13, -0.07, -0.40)
        );

        LongTermCycleSnapshot snapshot = service.evaluate(
                "002714",
                "牧原股份",
                "畜禽养殖",
                financials,
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("EARLY_RECOVERY");
        assertThat(snapshot.provisional()).isTrue();
        assertThat(snapshot.confidence()).isLessThan(70);
        assertThat(snapshot.dataGaps()).contains("缺少可核验的产品价格、库存和产能证据");
        assertThat(snapshot.supportingEvidence()).contains("ROE 9% → 16%");
    }

    @Test
    void usesStableTemplateForWeakCycleConsumerIndustry() {
        LongTermIndustryContext industry = service.classifyIndustry("乳制品");
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600887",
                "伊利股份",
                "乳制品",
                List.of(
                        annual("2025-12-31", 0.20, 0.34, 0.05, 0.08),
                        annual("2024-12-31", 0.19, 0.33, 0.04, 0.07),
                        annual("2023-12-31", 0.18, 0.32, 0.03, 0.06)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(industry.cycleType()).isEqualTo("WEAK_CYCLE");
        assertThat(industry.modelCode()).isEqualTo("STANDARD");
        assertThat(snapshot.businessStage()).isEqualTo("STABLE");
        assertThat(snapshot.provisional()).isFalse();
    }

    @Test
    void separatesPriceRecoveryFromBusinessCycle() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                List.of(
                        annual("2025-12-31", 0.12, 0.40, 0.15, 0.20),
                        annual("2024-12-31", 0.11, 0.39, 0.12, 0.18),
                        annual("2023-12-31", 0.10, 0.38, 0.10, 0.16)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.priceStage()).isEqualTo("RECOVERY");
        assertThat(snapshot.priceStageLabel()).isEqualTo("价格修复");
    }

    @Test
    void labelsStableStandardIndustryAsBusinessStable() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600987",
                "航民股份",
                "纺织制造",
                List.of(
                        annual("2025-12-31", 0.12, 0.24, 0.05, 0.07),
                        annual("2024-12-31", 0.12, 0.24, 0.04, 0.06),
                        annual("2023-12-31", 0.11, 0.23, 0.04, 0.05)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("STABLE");
        assertThat(snapshot.businessStageLabel()).isEqualTo("经营稳定");
    }

    @Test
    void treatsAProfitReversalWithBroadDeteriorationAsContraction() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "300979",
                "华利集团",
                "纺织制造",
                List.of(
                        annual("2025-12-31", 0.19, 0.22, 0.04, -0.17),
                        annual("2024-12-31", 0.24, 0.27, 0.19, 0.20),
                        annual("2023-12-31", 0.22, 0.25, 0.12, 0.16)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("CONTRACTION");
        assertThat(snapshot.businessStageLabel()).isEqualTo("经营收缩");
    }

    @Test
    void detectsAnOverheatedPriceLocation() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                List.of(),
                risingKLines(180, 10, 30),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.priceStage()).isEqualTo("OVERHEATED");
    }

    @Test
    void detectsAnEstablishedRisingTrendAsExpansion() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                List.of(),
                risingKLines(180, 10, 16),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.priceStage()).isEqualTo("EXPANSION");
        assertThat(snapshot.supportingEvidence())
                .anyMatch(item -> item.startsWith("120日/180日价格区间位置"));
        assertThat(snapshot.dataGaps()).contains("近一年K线不足250个交易日，未形成完整250日区间");
    }

    @Test
    void doesNotTreatEmptyFinancialRowsAsBusinessEvidence() {
        List<EastMoneyAnnualIndicator> emptyFinancials = List.of(
                annualWithoutGrowth("2025-12-31"),
                annualWithoutGrowth("2024-12-31"),
                annualWithoutGrowth("2023-12-31")
        );

        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                emptyFinancials,
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of("政策证据缺失"))
        );

        assertThat(snapshot.businessStage()).isEqualTo("INSUFFICIENT");
        assertThat(snapshot.confidence()).isLessThan(50);
        assertThat(snapshot.dataGaps()).contains("有效营收与净利润增速不足三期", "政策证据缺失");
    }

    @Test
    void marksPriceCycleInsufficientWhenKLineHistoryIsShort() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                List.of(),
                risingKLines(30, 10, 12),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.priceStage()).isEqualTo("INSUFFICIENT");
        assertThat(snapshot.dataGaps()).contains("近一年K线不足120个交易日");
    }

    @Test
    void treatsTheIndustryPlaceholderAsUnknownEvidence() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "行业待补",
                List.of(
                        annual("2025-12-31", 0.20, 0.30, 0.20, 0.25),
                        annual("2024-12-31", 0.18, 0.28, 0.15, 0.18),
                        annual("2023-12-31", 0.16, 0.26, 0.10, 0.12)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("INSUFFICIENT");
        assertThat(snapshot.provisional()).isTrue();
        assertThat(snapshot.dataGaps()).contains("服务端未确认所属行业");
    }

    @Test
    void duplicateReportYearsDoNotSatisfyTheThreeYearGate() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "600000",
                "样本公司",
                "软件开发",
                List.of(
                        annual("2025-12-31", 0.20, 0.30, 0.20, 0.25),
                        annual("2025-06-30", 0.18, 0.28, 0.15, 0.18),
                        annual("2024-12-31", 0.16, 0.26, 0.10, 0.12)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("INSUFFICIENT");
        assertThat(snapshot.dataGaps()).contains("年度财务历史不足三期");
    }

    @Test
    void usesTheMultiYearBaselineForStrongCycleDirection() {
        LongTermCycleSnapshot snapshot = service.evaluate(
                "002714",
                "牧原股份",
                "畜禽养殖",
                List.of(
                        annual("2025-12-31", 0.10, 0.18, 0.05, 0.05),
                        annual("2024-12-31", 0.11, 0.19, 0.06, 0.06),
                        annual("2023-12-31", 0.02, 0.08, -0.50, -0.50)
                ),
                recoveryKLines(),
                new LongTermPolicyEvidence(List.of(), List.of())
        );

        assertThat(snapshot.businessStage()).isEqualTo("EARLY_RECOVERY");
        assertThat(snapshot.supportingEvidence())
                .contains("历史营收/净利润增速基准 -22%/-22%");
    }

    private EastMoneyAnnualIndicator annual(
            String date,
            double roe,
            double grossMargin,
            double revenueGrowth,
            double profitGrowth
    ) {
        return new EastMoneyAnnualIndicator(
                "000001",
                "样本",
                date,
                "ANNUAL",
                decimal(roe),
                decimal(1.2),
                decimal(grossMargin),
                decimal(revenueGrowth),
                decimal(profitGrowth),
                decimal(1),
                decimal(5)
        );
    }

    private List<EastMoneyKLine> recoveryKLines() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int index = 0; index < 60; index++) {
            rows.add(kline(start.plusDays(index), 15 - index * 0.015));
        }
        for (int index = 0; index < 60; index++) {
            rows.add(kline(start.plusDays(60 + index), 14.1 - index * 0.068));
        }
        for (int index = 0; index < 30; index++) {
            rows.add(kline(start.plusDays(120 + index), 10.1 + index * 0.10));
        }
        return rows;
    }

    private EastMoneyAnnualIndicator annualWithoutGrowth(String date) {
        return new EastMoneyAnnualIndicator(
                "000001",
                "样本",
                date,
                "ANNUAL",
                decimal(0.12),
                decimal(1.2),
                decimal(0.25),
                null,
                null,
                decimal(1),
                decimal(5)
        );
    }

    private List<EastMoneyKLine> risingKLines(int count, double begin, double end) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int index = 0; index < count; index++) {
            double ratio = count == 1 ? 1 : index / (double) (count - 1);
            rows.add(kline(start.plusDays(index), begin + (end - begin) * ratio));
        }
        return rows;
    }

    private EastMoneyKLine kline(LocalDate date, double close) {
        BigDecimal value = decimal(close);
        return new EastMoneyKLine(
                "600000",
                date,
                value,
                value,
                value.multiply(decimal(1.01)),
                value.multiply(decimal(0.99)),
                decimal(1_000_000),
                decimal(10_000_000)
        );
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
