package com.aistock.research.financial;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class FinancialHistoryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final CompanyService companyService;
    private final EastMoneyClient eastMoneyClient;

    public FinancialHistoryService(CompanyService companyService, EastMoneyClient eastMoneyClient) {
        this.companyService = companyService;
        this.eastMoneyClient = eastMoneyClient;
    }

    public FinancialHistoryReport history(String symbol) {
        return history(symbol, 10);
    }

    public FinancialHistoryReport history(String symbol, int years) {
        CompanyProfile company = companyService.getCompany(symbol);
        return history(company, years);
    }

    public FinancialHistoryReport history(CompanyProfile company, int years) {
        List<FinancialMetricPoint> points = fetchPoints(company, years);
        BigDecimal averageRoe = average(points.stream().map(FinancialMetricPoint::roe).toList());
        BigDecimal averageGrossMargin = average(points.stream().map(FinancialMetricPoint::grossMargin).toList());
        BigDecimal averageRevenueGrowth = average(points.stream().map(FinancialMetricPoint::revenueGrowth).toList());
        BigDecimal averageNetProfitGrowth = average(points.stream().map(FinancialMetricPoint::netProfitGrowth).toList());
        int positiveCashFlowYears = (int) points.stream()
                .map(FinancialMetricPoint::operatingCashFlowPerShare)
                .filter(Objects::nonNull)
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .count();
        int negativeRevenueGrowthYears = (int) points.stream()
                .map(FinancialMetricPoint::revenueGrowth)
                .filter(Objects::nonNull)
                .filter(value -> value.compareTo(BigDecimal.ZERO) < 0)
                .count();
        BigDecimal qualityScore = qualityScore(points, averageRoe, averageGrossMargin, averageRevenueGrowth, positiveCashFlowYears, negativeRevenueGrowthYears);
        Status status = status(points, qualityScore);
        return new FinancialHistoryReport(
                company.symbol(),
                company.name(),
                status.code(),
                status.label(),
                points.size(),
                qualityScore,
                averageRoe,
                averageGrossMargin,
                averageRevenueGrowth,
                averageNetProfitGrowth,
                positiveCashFlowYears,
                negativeRevenueGrowthYears,
                points,
                conclusions(points, qualityScore, averageRoe, averageGrossMargin, positiveCashFlowYears, negativeRevenueGrowthYears),
                dataGaps(points, years),
                Instant.now()
        );
    }

    private List<FinancialMetricPoint> fetchPoints(CompanyProfile company, int years) {
        try {
            return eastMoneyClient.fetchAnnualIndicatorHistory(company.symbol(), years).stream()
                    .filter(indicator -> isYearEndReportDate(indicator.reportDate()))
                    .map(indicator -> point(company, indicator))
                    .sorted(Comparator.comparing(FinancialMetricPoint::reportDate).reversed())
                    .limit(years)
                    .toList();
        } catch (IllegalStateException exception) {
            return fallbackPoint(company);
        }
    }

    private FinancialMetricPoint point(CompanyProfile company, EastMoneyAnnualIndicator indicator) {
        return new FinancialMetricPoint(
                indicator.symbol(),
                indicator.name() == null ? company.name() : indicator.name(),
                indicator.reportDate(),
                indicator.dataType(),
                indicator.roe(),
                indicator.operatingCashFlowPerShare(),
                indicator.grossMargin(),
                indicator.revenueGrowth(),
                indicator.netProfitGrowth(),
                indicator.eps(),
                indicator.bps()
        );
    }

    private List<FinancialMetricPoint> fallbackPoint(CompanyProfile company) {
        if (company.financialReportDate() == null || company.financialReportDate().isBlank()) {
            return List.of();
        }
        return List.of(new FinancialMetricPoint(
                company.symbol(),
                company.name(),
                company.financialReportDate(),
                company.financialDataType(),
                company.factors().get("roe_annual"),
                company.factors().get("operating_cash_flow_per_share"),
                company.factors().get("gross_margin"),
                company.factors().get("revenue_growth"),
                company.factors().get("net_profit_growth"),
                company.factors().get("eps"),
                company.factors().get("bps")
        ));
    }

    private BigDecimal qualityScore(
            List<FinancialMetricPoint> points,
            BigDecimal averageRoe,
            BigDecimal averageGrossMargin,
            BigDecimal averageRevenueGrowth,
            int positiveCashFlowYears,
            int negativeRevenueGrowthYears
    ) {
        if (points.isEmpty()) {
            return ZERO;
        }
        BigDecimal sampleScore = BigDecimal.valueOf(Math.min(points.size(), 10)).multiply(new BigDecimal("6.00"));
        BigDecimal roeScore = boundedPercent(averageRoe, "0.04", "0.18").multiply(new BigDecimal("22.00"));
        BigDecimal marginScore = boundedPercent(averageGrossMargin, "0.15", "0.55").multiply(new BigDecimal("18.00"));
        BigDecimal growthScore = boundedPercent(averageRevenueGrowth, "-0.08", "0.18").multiply(new BigDecimal("15.00"));
        BigDecimal cashScore = BigDecimal.valueOf(positiveCashFlowYears)
                .divide(BigDecimal.valueOf(Math.max(points.size(), 1)), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("20.00"));
        BigDecimal declinePenalty = BigDecimal.valueOf(negativeRevenueGrowthYears).multiply(new BigDecimal("3.00"));
        return clamp(sampleScore.add(roeScore).add(marginScore).add(growthScore).add(cashScore).subtract(declinePenalty));
    }

    private BigDecimal boundedPercent(BigDecimal value, String low, String high) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal min = new BigDecimal(low);
        BigDecimal max = new BigDecimal(high);
        if (value.compareTo(min) <= 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(max) >= 0) {
            return BigDecimal.ONE;
        }
        return value.subtract(min).divide(max.subtract(min), 4, RoundingMode.HALF_UP);
    }

    private Status status(List<FinancialMetricPoint> points, BigDecimal qualityScore) {
        if (points.size() >= 8 && qualityScore.compareTo(new BigDecimal("72.00")) >= 0) {
            return new Status("STRONG_SEQUENCE", "多年质量较强");
        }
        if (points.size() >= 5 && qualityScore.compareTo(new BigDecimal("58.00")) >= 0) {
            return new Status("USABLE_SEQUENCE", "多年序列可用");
        }
        if (!points.isEmpty()) {
            return new Status("PARTIAL_SEQUENCE", "部分序列可用");
        }
        return new Status("MISSING_SEQUENCE", "历史序列缺失");
    }

    private List<String> conclusions(
            List<FinancialMetricPoint> points,
            BigDecimal qualityScore,
            BigDecimal averageRoe,
            BigDecimal averageGrossMargin,
            int positiveCashFlowYears,
            int negativeRevenueGrowthYears
    ) {
        if (points.isEmpty()) {
            return List.of("未获取到年度财务序列，质量判断不能越过数据门槛。");
        }
        List<String> conclusions = new ArrayList<>();
        conclusions.add("已获取 " + points.size() + " 个年度财务样本，财务历史质量分 " + qualityScore + "。");
        conclusions.add("平均 ROE " + percentText(averageRoe) + "，平均毛利率 " + percentText(averageGrossMargin) + "。");
        conclusions.add("经营现金流/股为正的年份 " + positiveCashFlowYears + " 个，营收同比为负的年份 " + negativeRevenueGrowthYears + " 个。");
        return conclusions;
    }

    private List<String> dataGaps(List<FinancialMetricPoint> points, int years) {
        List<String> gaps = new ArrayList<>();
        if (points.size() < years) {
            gaps.add("年度样本少于目标 " + years + " 年，需继续补历史数据。");
        }
        if (points.stream().anyMatch(point -> point.operatingCashFlowPerShare() == null)) {
            gaps.add("部分年份缺少经营现金流/股。");
        }
        gaps.add("仍缺资本开支，暂不能计算严格自由现金流。");
        gaps.add("仍缺历史 PE/PB 日频或月频序列，估值分位需要单独数据源。");
        return gaps.stream().distinct().toList();
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        return present.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return ZERO;
        }
        if (value.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String percentText(BigDecimal value) {
        if (value == null) {
            return "待补充";
        }
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private boolean isYearEndReportDate(String reportDate) {
        return reportDate != null && reportDate.contains("-12-31");
    }

    private record Status(String code, String label) {
    }
}
