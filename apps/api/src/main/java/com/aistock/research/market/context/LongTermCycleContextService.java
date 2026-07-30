package com.aistock.research.market.context;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LongTermCycleContextService {

    private static final List<String> FINANCIAL_TERMS = List.of("银行", "保险", "证券", "金融");
    private static final List<String> STRONG_CYCLE_TERMS =
            List.of("养殖", "畜禽", "农业", "煤炭", "有色", "钢铁", "化工", "水泥", "建材", "航运", "锂电", "电池");
    private static final List<String> WEAK_CYCLE_TERMS =
            List.of("乳制品", "食品", "饮料", "医药", "家电", "日用品", "公用事业", "电力", "燃气");
    private static final List<String> GROWTH_TERMS =
            List.of("软件", "半导体", "计算机", "通信", "电子", "人工智能", "互联网");

    public LongTermIndustryContext classifyIndustry(String industry) {
        if (industry == null
                || industry.isBlank()
                || industry.contains("待补")
                || industry.contains("未知")) {
            return new LongTermIndustryContext(
                    "行业待补",
                    "UNKNOWN",
                    "估值模型待补",
                    "UNKNOWN",
                    "行业属性待补",
                    List.of(),
                    List.of("服务端未确认所属行业")
            );
        }
        if (containsAny(industry, FINANCIAL_TERMS)) {
            return context(industry, "FINANCIAL", "金融行业模型", "FINANCIAL", "金融周期");
        }
        if (containsAny(industry, STRONG_CYCLE_TERMS)) {
            return context(industry, "CYCLICAL", "周期行业模型", "STRONG_CYCLE", "强周期");
        }
        if (containsAny(industry, WEAK_CYCLE_TERMS)) {
            return context(industry, "STANDARD", "普通企业模型", "WEAK_CYCLE", "弱周期");
        }
        if (containsAny(industry, GROWTH_TERMS)) {
            return context(industry, "GROWTH", "成长企业模型", "GROWTH", "成长行业");
        }
        return context(industry, "STANDARD", "普通企业模型", "STANDARD", "一般行业");
    }

    public LongTermCycleSnapshot evaluate(
            String symbol,
            String companyName,
            String industry,
            List<EastMoneyAnnualIndicator> financials,
            List<EastMoneyKLine> klines,
            LongTermPolicyEvidence policyEvidence
    ) {
        LongTermIndustryContext industryContext = classifyIndustry(industry);
        List<EastMoneyAnnualIndicator> orderedFinancials = safeFinancials(financials);
        List<EastMoneyAnnualIndicator> businessFinancials = orderedFinancials.stream()
                .filter(item -> item.revenueGrowth() != null && item.netProfitGrowth() != null)
                .toList();
        List<String> supporting = new ArrayList<>();
        List<String> contrary = new ArrayList<>();
        List<String> gaps = new ArrayList<>(industryContext.dataGaps());
        if (policyEvidence != null) {
            gaps.addAll(policyEvidence.dataGaps());
        }
        appendFinancialCoverageGaps(orderedFinancials, businessFinancials, gaps);

        String businessStage = businessStage(
                industryContext,
                businessFinancials,
                policyEvidence,
                supporting,
                contrary,
                gaps
        );
        PriceAssessment priceAssessment = assessPrice(klines, supporting, contrary, gaps);
        boolean strongCycle = "STRONG_CYCLE".equals(industryContext.cycleType());
        if (strongCycle) {
            gaps.add("缺少可核验的产品价格、库存和产能证据");
        }
        int confidence = confidence(industryContext, businessFinancials, priceAssessment, gaps);
        if (strongCycle) {
            confidence = Math.min(confidence, 69);
        }
        if ("INSUFFICIENT".equals(businessStage)) {
            confidence = Math.min(confidence, 49);
        }
        boolean provisional = strongCycle || "INSUFFICIENT".equals(businessStage);

        return new LongTermCycleSnapshot(
                businessStage,
                businessStageLabel(businessStage, industryContext.cycleType()),
                priceAssessment.stage(),
                priceStageLabel(priceAssessment.stage()),
                confidence,
                provisional,
                List.copyOf(supporting),
                List.copyOf(contrary),
                gaps.stream().distinct().toList()
        );
    }

    private String businessStage(
            LongTermIndustryContext industry,
            List<EastMoneyAnnualIndicator> financials,
            LongTermPolicyEvidence policyEvidence,
            List<String> supporting,
            List<String> contrary,
            List<String> gaps
    ) {
        if ("UNKNOWN".equals(industry.cycleType())) {
            return "INSUFFICIENT";
        }
        if (financials.size() < 3) {
            return "INSUFFICIENT";
        }

        EastMoneyAnnualIndicator latest = financials.get(0);
        EastMoneyAnnualIndicator previous = financials.get(1);
        appendFinancialEvidence(latest, previous, supporting, contrary);
        appendPolicyEvidence(policyEvidence, supporting, contrary);

        BigDecimal latestRevenueGrowth = value(latest.revenueGrowth());
        BigDecimal latestProfitGrowth = value(latest.netProfitGrowth());
        BigDecimal revenueBaseline = averageGrowth(
                financials.subList(1, Math.min(financials.size(), 5)),
                true
        );
        BigDecimal profitBaseline = averageGrowth(
                financials.subList(1, Math.min(financials.size(), 5)),
                false
        );
        supporting.add("历史营收/净利润增速基准 "
                + formatPercent(revenueBaseline) + "/" + formatPercent(profitBaseline));
        boolean revenueImproving = latestRevenueGrowth.compareTo(revenueBaseline) > 0;
        boolean profitImproving = latestProfitGrowth.compareTo(profitBaseline) > 0;
        boolean broadDeterioration = latestProfitGrowth.signum() < 0
                && !profitImproving
                && !revenueImproving;

        if ("WEAK_CYCLE".equals(industry.cycleType()) || "FINANCIAL".equals(industry.cycleType())) {
            return broadDeterioration ? "CONTRACTION" : "STABLE";
        }

        if ("STRONG_CYCLE".equals(industry.cycleType())) {
            if (revenueImproving && profitImproving) {
                return "EARLY_RECOVERY";
            }
            if (latestRevenueGrowth.signum() < 0 && latestProfitGrowth.signum() < 0) {
                return "CONTRACTION";
            }
            if (latestRevenueGrowth.signum() > 0 && latestProfitGrowth.signum() > 0) {
                return "EXPANSION";
            }
            return "BOTTOMING";
        }

        if (latestRevenueGrowth.compareTo(new BigDecimal("0.10")) >= 0
                && latestProfitGrowth.compareTo(new BigDecimal("0.10")) >= 0) {
            return "EXPANSION";
        }
        if (broadDeterioration
                || (latestRevenueGrowth.signum() < 0 && latestProfitGrowth.signum() < 0)) {
            return "CONTRACTION";
        }
        return "STABLE";
    }

    private PriceAssessment assessPrice(
            List<EastMoneyKLine> klines,
            List<String> supporting,
            List<String> contrary,
            List<String> gaps
    ) {
        List<EastMoneyKLine> rows = klines == null ? List.of() : klines.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (rows.size() < 120) {
            gaps.add("近一年K线不足120个交易日");
            return new PriceAssessment("INSUFFICIENT", false);
        }

        int longWindowDays = Math.min(250, rows.size());
        if (longWindowDays < 250) {
            gaps.add("近一年K线不足250个交易日，未形成完整250日区间");
        }
        List<EastMoneyKLine> window250 = rows.subList(rows.size() - longWindowDays, rows.size());
        List<EastMoneyKLine> window120 = rows.subList(rows.size() - 120, rows.size());
        BigDecimal latest = window250.get(window250.size() - 1).close();
        BigDecimal low250 = window250.stream().map(EastMoneyKLine::low)
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(latest);
        BigDecimal high250 = window250.stream().map(EastMoneyKLine::high)
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(latest);
        BigDecimal low120 = window120.stream().map(EastMoneyKLine::low)
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(latest);
        BigDecimal high120 = window120.stream().map(EastMoneyKLine::high)
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(latest);
        BigDecimal ma20 = averageClose(window250, 20);
        BigDecimal ma60 = averageClose(window250, 60);
        BigDecimal ma120 = averageClose(window250, 120);
        BigDecimal priorMa20 = averageCloseEnding(window250, 20, 20);
        BigDecimal priorMa60 = averageCloseEnding(window250, 60, 20);
        BigDecimal priorMa120 = averageCloseEnding(window250, 120, 20);
        double position120 = rangePosition(latest, low120, high120);
        double position250 = rangePosition(latest, low250, high250);
        double gainFromLow = low250.signum() == 0
                ? 0
                : latest.subtract(low250).divide(low250, 6, RoundingMode.HALF_UP).doubleValue();
        boolean ma20Rising = ma20.compareTo(priorMa20) > 0;
        boolean ma60Rising = ma60.compareTo(priorMa60) > 0;
        boolean ma120Rising = rows.size() >= 140 && ma120.compareTo(priorMa120) > 0;

        supporting.add("120日/" + longWindowDays + "日价格区间位置 "
                + Math.round(position120 * 100) + "%/" + Math.round(position250 * 100) + "%");
        supporting.add("MA20/MA60/MA120："
                + format(ma20) + "/" + format(ma60) + "/" + format(ma120));

        if (position250 >= 0.90 && gainFromLow >= 0.80 && ma20.compareTo(ma60) > 0) {
            contrary.add("价格接近一年高位，追高安全边际下降");
            return new PriceAssessment("OVERHEATED", true);
        }
        if (position250 <= 0.25 && position120 <= 0.35) {
            supporting.add("价格处于近一年低位区域");
            return new PriceAssessment("LOW", true);
        }
        if (ma20.compareTo(ma60) > 0
                && ma60.compareTo(ma120) > 0
                && latest.compareTo(ma20) >= 0
                && ma20Rising
                && ma60Rising
                && ma120Rising) {
            supporting.add("MA20、MA60、MA120多头排列且三条均线向上");
            return new PriceAssessment("EXPANSION", true);
        }
        if (ma20.compareTo(ma60) > 0 && latest.compareTo(ma20) >= 0 && ma20Rising) {
            supporting.add("MA20上穿并站在MA60上方，价格进入修复段");
            return new PriceAssessment("RECOVERY", true);
        }
        if (ma20.compareTo(ma60) < 0 && position250 < 0.55) {
            contrary.add("短期均线弱于中期均线，仍处于回落段");
            return new PriceAssessment("PULLBACK", true);
        }
        return new PriceAssessment("RANGE", true);
    }

    private void appendFinancialEvidence(
            EastMoneyAnnualIndicator latest,
            EastMoneyAnnualIndicator previous,
            List<String> supporting,
            List<String> contrary
    ) {
        appendChange("营收增速", latest.revenueGrowth(), previous.revenueGrowth(), supporting, contrary);
        appendChange("净利润增速", latest.netProfitGrowth(), previous.netProfitGrowth(), supporting, contrary);
        appendChange("毛利率", latest.grossMargin(), previous.grossMargin(), supporting, contrary);
        appendChange("ROE", latest.roe(), previous.roe(), supporting, contrary);
        appendAbsoluteChange(
                "每股经营现金流",
                latest.operatingCashFlowPerShare(),
                previous.operatingCashFlowPerShare(),
                supporting,
                contrary
        );
    }

    private void appendChange(
            String label,
            BigDecimal latest,
            BigDecimal previous,
            List<String> supporting,
            List<String> contrary
    ) {
        if (latest == null || previous == null) {
            return;
        }
        String text = label + " " + formatPercent(previous) + " → " + formatPercent(latest);
        if (latest.compareTo(previous) >= 0) {
            supporting.add(text);
        } else {
            contrary.add(text);
        }
    }

    private void appendPolicyEvidence(
            LongTermPolicyEvidence policyEvidence,
            List<String> supporting,
            List<String> contrary
    ) {
        if (policyEvidence == null) {
            return;
        }
        long supportCount = policyEvidence.documents().stream()
                .filter(document -> "SUPPORT".equals(document.impact()))
                .count();
        long constraintCount = policyEvidence.documents().stream()
                .filter(document -> "CONSTRAINT".equals(document.impact()))
                .count();
        if (supportCount > 0) {
            supporting.add("近两年匹配到 " + supportCount + " 条行业支持政策");
        }
        if (constraintCount > 0) {
            contrary.add("近两年匹配到 " + constraintCount + " 条行业约束政策");
        }
    }

    private void appendAbsoluteChange(
            String label,
            BigDecimal latest,
            BigDecimal previous,
            List<String> supporting,
            List<String> contrary
    ) {
        if (latest == null || previous == null) {
            return;
        }
        String text = label + " " + format(previous) + " → " + format(latest);
        if (latest.compareTo(previous) >= 0) {
            supporting.add(text);
        } else {
            contrary.add(text);
        }
    }

    private void appendFinancialCoverageGaps(
            List<EastMoneyAnnualIndicator> allFinancials,
            List<EastMoneyAnnualIndicator> businessFinancials,
            List<String> gaps
    ) {
        if (allFinancials.size() < 3) {
            gaps.add("年度财务历史不足三期");
        }
        if (businessFinancials.size() < 3) {
            gaps.add("有效营收与净利润增速不足三期");
        }
        if (allFinancials.stream().filter(item -> item.roe() != null).count() < 3) {
            gaps.add("ROE历史不足三期");
        }
        if (allFinancials.stream().filter(item -> item.grossMargin() != null).count() < 3) {
            gaps.add("毛利率历史不足三期");
        }
        if (allFinancials.stream().filter(item -> item.operatingCashFlowPerShare() != null).count() < 3) {
            gaps.add("经营现金流历史不足三期");
        }
    }

    private int confidence(
            LongTermIndustryContext industry,
            List<EastMoneyAnnualIndicator> financials,
            PriceAssessment price,
            List<String> gaps
    ) {
        int score = 30;
        if (!"UNKNOWN".equals(industry.cycleType())) {
            score += 15;
        }
        score += Math.min(25, financials.size() * 5);
        if (price.sufficient()) {
            score += 15;
        }
        score -= Math.min(20, gaps.size() * 5);
        return Math.max(10, Math.min(95, score));
    }

    private List<EastMoneyAnnualIndicator> safeFinancials(List<EastMoneyAnnualIndicator> financials) {
        if (financials == null) {
            return List.of();
        }
        Map<String, EastMoneyAnnualIndicator> byReportYear = new LinkedHashMap<>();
        financials.stream()
                .filter(item -> item != null && item.reportDate() != null)
                .sorted(Comparator.comparing(EastMoneyAnnualIndicator::reportDate).reversed())
                .forEach(item -> byReportYear.putIfAbsent(reportYear(item.reportDate()), item));
        return List.copyOf(byReportYear.values());
    }

    private String reportYear(String reportDate) {
        return reportDate.length() >= 4 ? reportDate.substring(0, 4) : reportDate;
    }

    private BigDecimal averageGrowth(List<EastMoneyAnnualIndicator> financials, boolean revenue) {
        BigDecimal sum = financials.stream()
                .map(item -> revenue ? item.revenueGrowth() : item.netProfitGrowth())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(financials.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal averageClose(List<EastMoneyKLine> rows, int period) {
        int from = Math.max(0, rows.size() - period);
        List<EastMoneyKLine> window = rows.subList(from, rows.size());
        BigDecimal sum = window.stream()
                .map(EastMoneyKLine::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(window.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal averageCloseEnding(List<EastMoneyKLine> rows, int period, int offset) {
        int end = Math.max(1, rows.size() - offset);
        int from = Math.max(0, end - period);
        List<EastMoneyKLine> window = rows.subList(from, end);
        BigDecimal sum = window.stream()
                .map(EastMoneyKLine::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(window.size()), 6, RoundingMode.HALF_UP);
    }

    private double rangePosition(BigDecimal latest, BigDecimal low, BigDecimal high) {
        BigDecimal range = high.subtract(low);
        if (range.signum() <= 0) {
            return 0.5;
        }
        return latest.subtract(low).divide(range, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private LongTermIndustryContext context(
            String industry,
            String modelCode,
            String modelLabel,
            String cycleType,
            String cycleTypeLabel
    ) {
        return new LongTermIndustryContext(
                industry,
                modelCode,
                modelLabel,
                cycleType,
                cycleTypeLabel,
                List.of("依据东方财富行业分类匹配长期估值与周期模板"),
                List.of()
        );
    }

    private boolean containsAny(String value, List<String> terms) {
        return value != null && terms.stream().anyMatch(value::contains);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatPercent(BigDecimal value) {
        return format(value.multiply(BigDecimal.valueOf(100))) + "%";
    }

    private String businessStageLabel(String stage, String cycleType) {
        return switch (stage) {
            case "BOTTOMING" -> "底部出清";
            case "EARLY_RECOVERY" -> "早期修复";
            case "EXPANSION" -> "经营扩张";
            case "OVERHEATED" -> "经营过热";
            case "CONTRACTION" -> "经营收缩";
            case "STABLE" -> "WEAK_CYCLE".equals(cycleType) || "FINANCIAL".equals(cycleType)
                    ? "弱周期稳定"
                    : "经营稳定";
            default -> "经营周期证据不足";
        };
    }

    private String priceStageLabel(String stage) {
        return switch (stage) {
            case "LOW" -> "价格低位";
            case "RECOVERY" -> "价格修复";
            case "EXPANSION" -> "趋势扩张";
            case "OVERHEATED" -> "高位拥挤";
            case "PULLBACK" -> "价格回落";
            case "RANGE" -> "区间整理";
            default -> "价格周期证据不足";
        };
    }

    private record PriceAssessment(String stage, boolean sufficient) {
    }
}
