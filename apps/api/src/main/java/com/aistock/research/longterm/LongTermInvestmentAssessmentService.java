package com.aistock.research.longterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.valuation.ValuationModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LongTermInvestmentAssessmentService {

    public static final String STRATEGY_VERSION = "long-term-value-discipline-v1";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int HISTORY_YEARS = 5;

    public LongTermInvestmentAssessment assess(LongTermAssessmentInput input) {
        Objects.requireNonNull(input, "input");
        String modelCode = modelCode(input);
        List<EastMoneyAnnualIndicator> history = annualHistory(input.annualHistory());
        LongTermFinancialQuality quality = financialQuality(history, modelCode);
        LongTermValuationExpectation valuation = valuation(input, history, quality, modelCode);
        BigDecimal moatScore = moatScore(input);
        BigDecimal capitalAllocationScore = capitalAllocationScore(history, quality);
        BigDecimal evidenceRiskScore = evidenceRiskScore(input, history, quality, valuation);
        BigDecimal valuationScore = valuationScore(valuation);
        BigDecimal qualityScore = qualityScore(quality, modelCode);
        BigDecimal overall = weighted(qualityScore, "0.30")
                .add(weighted(moatScore, "0.25"))
                .add(weighted(valuationScore, "0.25"))
                .add(weighted(capitalAllocationScore, "0.10"))
                .add(weighted(evidenceRiskScore, "0.10"));
        LongTermFactorScores factorScores = new LongTermFactorScores(
                scale(qualityScore),
                scale(moatScore),
                scale(valuationScore),
                scale(capitalAllocationScore),
                scale(evidenceRiskScore),
                scale(overall)
        );
        List<String> executionDataGaps = merge(
                quality.dataGaps(),
                valuation.dataGaps(),
                modelDataGaps(modelCode),
                input.sourceDataGaps()
        );
        List<String> dataGaps = merge(
                executionDataGaps,
                List.of("持仓跌幅、财报披露和行业事件尚未接入自动复核调度。")
        );
        String status = assessmentStatus(history, valuation, factorScores, executionDataGaps);
        return new LongTermInvestmentAssessment(
                STRATEGY_VERSION,
                modelCode,
                modelLabel(modelCode),
                status,
                statusLabel(status),
                factorScores,
                quality,
                valuation,
                positionDiscipline(),
                logicAudit(),
                assessmentEvidence(input, quality, valuation, factorScores),
                assessmentRisks(quality, valuation, dataGaps),
                dataGaps
        );
    }

    private LongTermFinancialQuality financialQuality(
            List<EastMoneyAnnualIndicator> history,
            String modelCode
    ) {
        BigDecimal roeReference = switch (modelCode) {
            case "CYCLICAL" -> new BigDecimal("0.08");
            case "FINANCIAL" -> new BigDecimal("0.10");
            default -> new BigDecimal("0.12");
        };
        List<BigDecimal> roes = values(history, EastMoneyAnnualIndicator::roe, false);
        BigDecimal medianRoe = median(roes);
        int roeReferenceMetYears = (int) roes.stream()
                .filter(value -> value.compareTo(roeReference) >= 0)
                .count();
        int positiveCashFlowYears = (int) history.stream()
                .map(EastMoneyAnnualIndicator::operatingCashFlowPerShare)
                .filter(this::positive)
                .count();
        BigDecimal cashToProfitRatio = "FINANCIAL".equals(modelCode)
                ? null
                : cashToProfitRatio(history);
        BigDecimal grossMarginRange = range(values(history, EastMoneyAnnualIndicator::grossMargin, false));
        List<String> evidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        evidence.add("近 " + history.size() + " 个年度样本，ROE 中位数 " + percent(medianRoe)
                + "，行业模板参考 " + percent(roeReference) + "。");
        evidence.add("ROE 达到模板参考值的年份 " + roeReferenceMetYears + "/" + history.size() + "。");
        if (!"FINANCIAL".equals(modelCode)) {
            evidence.add("经营现金流为正年份 " + positiveCashFlowYears + "/" + history.size()
                    + "，累计现金/利润代理 " + multiple(cashToProfitRatio) + "。");
        } else {
            evidence.add("金融行业不使用普通企业经营现金流/净利润门槛。");
        }
        if (grossMarginRange != null) {
            evidence.add("多年毛利率区间差 " + percent(grossMarginRange) + "。");
        } else if (!"FINANCIAL".equals(modelCode)) {
            gaps.add("毛利率历史样本不足，盈利稳定性只能部分判断。");
        }
        if (history.size() < 3) {
            gaps.add("年度财务样本少于 3 年，不能形成可执行的长期质量判断。");
        } else if (history.size() < HISTORY_YEARS) {
            gaps.add("年度财务样本少于 5 年，持续性结论置信度下降。");
        }
        if (!"FINANCIAL".equals(modelCode) && cashToProfitRatio == null) {
            gaps.add("EPS 或经营现金流/股不足，无法计算多年现金含金量。");
        }
        gaps.add("尚缺完整资产负债率、利息保障倍数和债务到期结构。");
        String status = history.size() >= HISTORY_YEARS
                && medianRoe != null
                && medianRoe.compareTo(roeReference) >= 0
                && ("FINANCIAL".equals(modelCode) || positiveCashFlowYears >= 3)
                ? "DURABLE"
                : history.size() >= 3 ? "REVIEWABLE" : "INSUFFICIENT";
        return new LongTermFinancialQuality(
                history.size(),
                scaleRatio(medianRoe),
                roeReference,
                roeReferenceMetYears,
                positiveCashFlowYears,
                scaleRatio(cashToProfitRatio),
                scaleRatio(grossMarginRange),
                status,
                switch (status) {
                    case "DURABLE" -> "多年质量较稳";
                    case "REVIEWABLE" -> "质量可研究";
                    default -> "质量证据不足";
                },
                List.copyOf(evidence),
                List.copyOf(gaps)
        );
    }

    private BigDecimal qualityScore(LongTermFinancialQuality quality, String modelCode) {
        BigDecimal sampleScore = BigDecimal.valueOf(Math.min(quality.sampleYears(), HISTORY_YEARS))
                .divide(BigDecimal.valueOf(HISTORY_YEARS), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("15"));
        BigDecimal roeScore = ratioScore(quality.medianRoe(), quality.roeReference()).multiply(new BigDecimal("35"));
        BigDecimal persistenceScore = quality.sampleYears() == 0
                ? ZERO
                : BigDecimal.valueOf(quality.roeReferenceMetYears())
                .divide(BigDecimal.valueOf(quality.sampleYears()), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("20"));
        BigDecimal cashScore;
        if ("FINANCIAL".equals(modelCode)) {
            cashScore = new BigDecimal("15");
        } else {
            BigDecimal positiveYearsScore = quality.sampleYears() == 0
                    ? ZERO
                    : BigDecimal.valueOf(quality.positiveCashFlowYears())
                    .divide(BigDecimal.valueOf(quality.sampleYears()), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("10"));
            BigDecimal conversionScore = quality.cumulativeCashToProfitRatio() == null
                    ? ZERO
                    : quality.cumulativeCashToProfitRatio().min(BigDecimal.ONE)
                    .max(ZERO)
                    .multiply(new BigDecimal("10"));
            cashScore = positiveYearsScore.add(conversionScore);
        }
        BigDecimal stabilityScore = quality.grossMarginRange() == null
                ? new BigDecimal("5")
                : quality.grossMarginRange().compareTo(new BigDecimal("0.08")) <= 0
                ? new BigDecimal("10")
                : quality.grossMarginRange().compareTo(new BigDecimal("0.18")) <= 0
                ? new BigDecimal("6")
                : new BigDecimal("2");
        return clamp(sampleScore.add(roeScore).add(persistenceScore).add(cashScore).add(stabilityScore));
    }

    private LongTermValuationExpectation valuation(
            LongTermAssessmentInput input,
            List<EastMoneyAnnualIndicator> history,
            LongTermFinancialQuality quality,
            String modelCode
    ) {
        if ("FINANCIAL".equals(modelCode)) {
            return financialValuation(input, history, quality);
        }
        return ownerEarningsValuation(input, history, "CYCLICAL".equals(modelCode));
    }

    private LongTermValuationExpectation ownerEarningsValuation(
            LongTermAssessmentInput input,
            List<EastMoneyAnnualIndicator> history,
            boolean cyclical
    ) {
        List<BigDecimal> ownerEarnings = history.stream()
                .map(cyclical ? this::cycleOwnerEarningsProxy : this::ownerEarningsProxy)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal normalizedOwnerEarnings = cyclical ? average(ownerEarnings) : median(ownerEarnings);
        BigDecimal evidenceGrowth = normalGrowth(history, cyclical);
        BigDecimal discountRate = cyclical ? new BigDecimal("0.11") : new BigDecimal("0.10");
        BigDecimal terminalGrowth = cyclical ? new BigDecimal("0.02") : new BigDecimal("0.03");
        BigDecimal marginOfSafety = cyclical ? new BigDecimal("35") : new BigDecimal("25");
        List<String> evidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (!positive(normalizedOwnerEarnings) || !positive(input.latestPrice())) {
            gaps.add(cyclical && normalizedOwnerEarnings != null
                    ? "完整周期平均经营者收益不为正，当前不能用 DCF 形成价值区间。"
                    : "经营者收益代理或当前价格缺失，无法倒推市场隐含增长率。");
            gaps.add("缺少资本开支，当前不能计算严格自由现金流。");
            return missingValuation(marginOfSafety, cyclical, evidence, gaps);
        }
        BigDecimal impliedGrowth = solveImpliedGrowth(
                input.latestPrice(),
                normalizedOwnerEarnings,
                discountRate,
                terminalGrowth
        );
        BigDecimal lowGrowth = clampGrowth(evidenceGrowth.subtract(cyclical
                ? new BigDecimal("0.04") : new BigDecimal("0.03")), cyclical);
        BigDecimal highGrowth = clampOptimisticGrowth(evidenceGrowth.add(cyclical
                ? new BigDecimal("0.03") : new BigDecimal("0.04")), cyclical);
        BigDecimal pessimistic = presentValue(normalizedOwnerEarnings, lowGrowth, discountRate, terminalGrowth);
        BigDecimal base = presentValue(normalizedOwnerEarnings, evidenceGrowth, discountRate, terminalGrowth);
        BigDecimal optimistic = presentValue(normalizedOwnerEarnings, highGrowth, discountRate, terminalGrowth);
        BigDecimal discountToBase = discountPercent(input.latestPrice(), base);
        BigDecimal entryReference = positive(base)
                ? base.multiply(BigDecimal.ONE.subtract(marginOfSafety.divide(HUNDRED, 8, RoundingMode.HALF_UP)))
                : null;
        evidence.add((cyclical ? "完整周期平均" : "五年中位数") + "经营者收益代理 "
                + plain(normalizedOwnerEarnings) + " 元/股。");
        evidence.add("市场价格隐含未来十年增长约 " + percentPoints(impliedGrowth)
                + "，历史经营证据增长中枢约 " + percentPoints(evidenceGrowth) + "。");
        evidence.add("模型折现率 " + percent(discountRate) + "，长期增长率 " + percent(terminalGrowth)
                + "，价值区间不代表精确目标价。");
        gaps.add("缺少资本开支，当前使用 EPS 与经营现金流/股的保守代理，不是严格自由现金流。");
        if (ownerEarnings.size() < 3) {
            gaps.add("可用经营者收益代理少于 3 年，反向估值置信度较低。");
        }
        if (cyclical && ownerEarnings.size() < history.size()) {
            gaps.add("部分周期年份缺少 EPS 或经营现金流/股，完整周期平均仍不完整。");
        }
        if (cyclical) {
            gaps.add("周期行业仍缺产品价格、库存、产能出清、单位成本和供需验证。");
        }
        boolean completeOwnerEarningsSample = cyclical
                ? history.size() >= HISTORY_YEARS && ownerEarnings.size() == history.size()
                : history.size() >= HISTORY_YEARS && ownerEarnings.size() >= 3;
        String confidence = completeOwnerEarningsSample ? "MEDIUM" : "LOW";
        return new LongTermValuationExpectation(
                "IMPLIED_GROWTH",
                "市场隐含增长",
                percentPointValue(impliedGrowth),
                percentPointValue(evidenceGrowth),
                money(pessimistic),
                money(base),
                money(optimistic),
                scale(discountToBase),
                marginOfSafety,
                money(entryReference),
                cyclical,
                confidence,
                "MEDIUM".equals(confidence) ? "代理模型可用" : "代理模型待补证",
                List.copyOf(evidence),
                List.copyOf(gaps)
        );
    }

    private LongTermValuationExpectation financialValuation(
            LongTermAssessmentInput input,
            List<EastMoneyAnnualIndicator> history,
            LongTermFinancialQuality quality
    ) {
        BigDecimal discountRate = new BigDecimal("0.10");
        BigDecimal growth = new BigDecimal("0.03");
        BigDecimal marginOfSafety = new BigDecimal("20");
        BigDecimal pb = input.valuationContext() == null ? null : input.valuationContext().rawPb();
        BigDecimal medianRoe = quality.medianRoe();
        BigDecimal bps = firstPositive(history.stream().map(EastMoneyAnnualIndicator::bps).toList());
        List<String> evidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (!positive(pb) || !positive(medianRoe) || !positive(bps)) {
            gaps.add("PB、BPS 或多年 ROE 缺失，无法计算金融行业隐含 ROE。");
            gaps.add("仍缺不良率、拨备覆盖率和资本充足率等金融行业核心证据。");
            return new LongTermValuationExpectation(
                    "IMPLIED_ROE",
                    "市场隐含 ROE",
                    null,
                    percentPointValue(medianRoe),
                    null,
                    null,
                    null,
                    null,
                    marginOfSafety,
                    null,
                    false,
                    "LOW",
                    "金融估值待补证",
                    List.copyOf(evidence),
                    List.copyOf(gaps)
            );
        }
        BigDecimal impliedRoe = pb.multiply(discountRate.subtract(growth)).add(growth);
        BigDecimal lowRoe = medianRoe.subtract(new BigDecimal("0.02")).max(growth.add(new BigDecimal("0.005")));
        BigDecimal highRoe = medianRoe.add(new BigDecimal("0.02"));
        BigDecimal pessimistic = justifiedPb(lowRoe, discountRate, growth).multiply(bps);
        BigDecimal base = justifiedPb(medianRoe, discountRate, growth).multiply(bps);
        BigDecimal optimistic = justifiedPb(highRoe, discountRate, growth).multiply(bps);
        BigDecimal discountToBase = discountPercent(input.latestPrice(), base);
        BigDecimal entryReference = base.multiply(BigDecimal.ONE.subtract(
                marginOfSafety.divide(HUNDRED, 8, RoundingMode.HALF_UP)
        ));
        evidence.add("当前 PB " + plain(pb) + " 倒推长期 ROE 约 " + percent(impliedRoe) + "。");
        evidence.add("近年 ROE 中位数 " + percent(medianRoe) + "，每股净资产 " + plain(bps) + " 元。");
        evidence.add("采用 PB-ROE/剩余收益代理，不套用普通企业经营现金流 DCF。");
        gaps.add("仍缺不良率、拨备覆盖率、资本充足率和净息差等金融行业核心证据。");
        return new LongTermValuationExpectation(
                "IMPLIED_ROE",
                "市场隐含 ROE",
                percentPointValue(impliedRoe),
                percentPointValue(medianRoe),
                money(pessimistic),
                money(base),
                money(optimistic),
                scale(discountToBase),
                marginOfSafety,
                money(entryReference),
                false,
                history.size() >= HISTORY_YEARS ? "MEDIUM" : "LOW",
                history.size() >= HISTORY_YEARS ? "金融代理模型可用" : "金融代理模型待补证",
                List.copyOf(evidence),
                List.copyOf(gaps)
        );
    }

    private LongTermValuationExpectation missingValuation(
            BigDecimal marginOfSafety,
            boolean cyclical,
            List<String> evidence,
            List<String> gaps
    ) {
        return new LongTermValuationExpectation(
                "IMPLIED_GROWTH",
                "市场隐含增长",
                null,
                null,
                null,
                null,
                null,
                null,
                marginOfSafety,
                null,
                cyclical,
                "LOW",
                "反向估值待补证",
                List.copyOf(evidence),
                List.copyOf(gaps)
        );
    }

    private BigDecimal valuationScore(LongTermValuationExpectation valuation) {
        if (valuation.impliedExpectationPercent() == null || valuation.evidenceExpectationPercent() == null) {
            return new BigDecimal("40");
        }
        BigDecimal expectationGap = valuation.evidenceExpectationPercent()
                .subtract(valuation.impliedExpectationPercent());
        BigDecimal discountContribution = valuation.discountToBasePercent() == null
                ? ZERO
                : valuation.discountToBasePercent().multiply(new BigDecimal("0.45"));
        return clamp(new BigDecimal("55")
                .add(expectationGap.multiply(new BigDecimal("2.2")))
                .add(discountContribution));
    }

    private BigDecimal moatScore(LongTermAssessmentInput input) {
        BigDecimal percentile = input.industryRankPercentile();
        if (!input.industryRankRevenueBased()) {
            return input.assetAdvantagedIndustry() ? new BigDecimal("40") : new BigDecimal("35");
        }
        BigDecimal score;
        if (percentile == null || input.industrySampleCount() < 2) {
            score = new BigDecimal("45");
        } else if (percentile.compareTo(new BigDecimal("20")) <= 0) {
            score = new BigDecimal("90");
        } else if (percentile.compareTo(new BigDecimal("35")) <= 0) {
            score = new BigDecimal("80");
        } else if (percentile.compareTo(new BigDecimal("50")) <= 0) {
            score = new BigDecimal("65");
        } else {
            score = new BigDecimal("48");
        }
        if (input.assetAdvantagedIndustry()) {
            score = score.add(new BigDecimal("5"));
        }
        return clamp(score);
    }

    private BigDecimal capitalAllocationScore(
            List<EastMoneyAnnualIndicator> history,
            LongTermFinancialQuality quality
    ) {
        if (history.isEmpty()) {
            return new BigDecimal("30");
        }
        long dividendYears = history.stream().filter(this::hasCashDividend).count();
        BigDecimal dividendScore = BigDecimal.valueOf(dividendYears)
                .divide(BigDecimal.valueOf(history.size()), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("55"));
        BigDecimal returnScore = ratioScore(quality.medianRoe(), quality.roeReference())
                .multiply(new BigDecimal("35"));
        BigDecimal continuityBonus = consecutiveDividendYears(history) >= 3
                ? new BigDecimal("10") : ZERO;
        return clamp(dividendScore.add(returnScore).add(continuityBonus));
    }

    private BigDecimal evidenceRiskScore(
            LongTermAssessmentInput input,
            List<EastMoneyAnnualIndicator> history,
            LongTermFinancialQuality quality,
            LongTermValuationExpectation valuation
    ) {
        BigDecimal sample = BigDecimal.valueOf(Math.min(history.size(), HISTORY_YEARS))
                .divide(BigDecimal.valueOf(HISTORY_YEARS), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("45"));
        BigDecimal industry = input.industryRankPercentile() == null
                ? new BigDecimal("5")
                : input.industryRankRevenueBased() ? new BigDecimal("25") : new BigDecimal("5");
        BigDecimal valuationEvidence = "MEDIUM".equals(valuation.confidence())
                ? new BigDecimal("20") : new BigDecimal("8");
        BigDecimal qualityEvidence = "INSUFFICIENT".equals(quality.status())
                ? ZERO : new BigDecimal("10");
        BigDecimal modelGapPenalty = switch (modelCode(input)) {
            case "CYCLICAL", "FINANCIAL" -> new BigDecimal("25");
            default -> new BigDecimal("15");
        };
        BigDecimal sourceGapPenalty = BigDecimal.valueOf(Math.min(
                input.sourceDataGaps() == null ? 0 : input.sourceDataGaps().size() * 8L,
                24L
        ));
        return clamp(sample.add(industry).add(valuationEvidence).add(qualityEvidence)
                .subtract(modelGapPenalty)
                .subtract(sourceGapPenalty));
    }

    private String assessmentStatus(
            List<EastMoneyAnnualIndicator> history,
            LongTermValuationExpectation valuation,
            LongTermFactorScores scores,
            List<String> executionDataGaps
    ) {
        if (history.size() < 3 || valuation.impliedExpectationPercent() == null) {
            return "EVIDENCE_REVIEW";
        }
        boolean targetMarginMet = valuation.discountToBasePercent() != null
                && valuation.targetMarginOfSafetyPercent() != null
                && valuation.discountToBasePercent().compareTo(valuation.targetMarginOfSafetyPercent()) >= 0;
        if (!targetMarginMet) {
            return "WATCH";
        }
        if ((executionDataGaps != null && !executionDataGaps.isEmpty())
                || scores.evidenceRiskScore().compareTo(new BigDecimal("60")) < 0) {
            return "EVIDENCE_REVIEW";
        }
        if (scores.overallScore().compareTo(new BigDecimal("72")) >= 0
                && targetMarginMet) {
            return "BUILD_ZONE_REVIEW";
        }
        return "WATCH";
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "BUILD_ZONE_REVIEW" -> "进入建仓复核";
            case "WATCH" -> "长期观察";
            default -> "证据待补";
        };
    }

    private List<String> assessmentEvidence(
            LongTermAssessmentInput input,
            LongTermFinancialQuality quality,
            LongTermValuationExpectation valuation,
            LongTermFactorScores scores
    ) {
        List<String> evidence = new ArrayList<>();
        evidence.add("行业模板=" + modelLabel(modelCode(input)) + "，综合分=" + plain(scores.overallScore()) + "。");
        evidence.addAll(quality.evidence());
        evidence.addAll(valuation.evidence());
        return List.copyOf(evidence);
    }

    private List<String> assessmentRisks(
            LongTermFinancialQuality quality,
            LongTermValuationExpectation valuation,
            List<String> dataGaps
    ) {
        List<String> risks = new ArrayList<>();
        if (!"DURABLE".equals(quality.status())) {
            risks.add("多年盈利与现金质量尚未达到高置信状态。");
        }
        if (!"MEDIUM".equals(valuation.confidence())) {
            risks.add("反向估值证据不足，不能据此直接形成买入动作。");
        }
        risks.add("价值区间对折现率、长期增长和代理现金流敏感，必须结合反证复核。");
        if (!dataGaps.isEmpty()) {
            risks.add("仍有 " + dataGaps.size() + " 项关键证据缺口。");
        }
        return List.copyOf(risks);
    }

    private List<String> modelDataGaps(String modelCode) {
        return switch (modelCode) {
            case "CYCLICAL" -> List.of("供需、库存、产能和单位成本仍需行业证据交叉验证。");
            case "FINANCIAL" -> List.of("不良率、拨备、资本充足率和净息差仍需金融行业数据源。");
            default -> List.of("护城河、市场份额和管理层资本配置仍需公告与行业证据复核。");
        };
    }

    private LongTermPositionDiscipline positionDiscipline() {
        return new LongTermPositionDiscipline(
                new BigDecimal("10"),
                new BigDecimal("50"),
                3,
                new BigDecimal("15"),
                List.of(
                        "当前价格进入模型安全边际研究区间",
                        "多年盈利质量与行业地位证据可用",
                        "重大风险和治理门禁均通过"
                ),
                List.of(
                        "原投资逻辑未被证伪",
                        "安全边际较上次决策扩大",
                        "经营现金流和偿债能力未恶化",
                        "没有新增治理或审计红旗"
                ),
                List.of(
                        "当前仅输出复核规则清单，尚未接入持仓价格与事件调度自动触发",
                        "买入后股价下跌15%只触发强制复核，不自动加仓",
                        "季度财报披露后重新计算现金质量和估值",
                        "行业价格、库存、产能或竞争格局发生显著变化时立即复核"
                )
        );
    }

    private LongTermLogicAudit logicAudit() {
        return new LongTermLogicAudit(
                "季度轻审计：盈利、现金流、毛利率、负债压力和行业数据。",
                "年度深审计：护城河、资本配置、竞争格局和估值正常化。",
                List.of(
                        "业绩预告或定期报告显著偏离原假设",
                        "非标审计意见、监管处罚或财务更正",
                        "重大并购、扩产、债务再融资或大股东质押减持",
                        "行业供需、产品价格或监管规则发生结构性变化"
                ),
                List.of(
                        "核心成本、品牌、渠道或客户壁垒不可逆恶化",
                        "管理层资本配置持续损害股东利益",
                        "偿债或持续经营风险突破硬门槛",
                        "正常化盈利假设被连续财报证伪且六个月内无法修复"
                ),
                "卖出后允许重新研究；只有逻辑重新成立且安全边际恢复，才可重新进入。"
        );
    }

    private BigDecimal cashToProfitRatio(List<EastMoneyAnnualIndicator> history) {
        BigDecimal cash = ZERO;
        BigDecimal profit = ZERO;
        for (EastMoneyAnnualIndicator indicator : history) {
            if (positive(indicator.operatingCashFlowPerShare()) && positive(indicator.eps())) {
                cash = cash.add(indicator.operatingCashFlowPerShare());
                profit = profit.add(indicator.eps());
            }
        }
        return positive(profit) ? cash.divide(profit, 4, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal ownerEarningsProxy(EastMoneyAnnualIndicator indicator) {
        if (indicator == null || !positive(indicator.eps()) || !positive(indicator.operatingCashFlowPerShare())) {
            return null;
        }
        return indicator.eps().min(indicator.operatingCashFlowPerShare());
    }

    private BigDecimal cycleOwnerEarningsProxy(EastMoneyAnnualIndicator indicator) {
        if (indicator == null
                || indicator.eps() == null
                || indicator.operatingCashFlowPerShare() == null) {
            return null;
        }
        return indicator.eps().min(indicator.operatingCashFlowPerShare());
    }

    private BigDecimal normalGrowth(List<EastMoneyAnnualIndicator> history, boolean cyclical) {
        BigDecimal medianGrowth = median(values(history, EastMoneyAnnualIndicator::revenueGrowth, false));
        BigDecimal fallback = cyclical ? ZERO : new BigDecimal("0.04");
        return clampGrowth(medianGrowth == null ? fallback : medianGrowth, cyclical);
    }

    private BigDecimal clampGrowth(BigDecimal growth, boolean cyclical) {
        BigDecimal min = cyclical ? new BigDecimal("-0.04") : new BigDecimal("-0.02");
        BigDecimal max = cyclical ? new BigDecimal("0.06") : new BigDecimal("0.12");
        return growth.max(min).min(max);
    }

    private BigDecimal clampOptimisticGrowth(BigDecimal growth, boolean cyclical) {
        BigDecimal min = cyclical ? new BigDecimal("-0.01") : new BigDecimal("0.02");
        BigDecimal max = cyclical ? new BigDecimal("0.09") : new BigDecimal("0.16");
        return growth.max(min).min(max);
    }

    private BigDecimal solveImpliedGrowth(
            BigDecimal price,
            BigDecimal ownerEarnings,
            BigDecimal discountRate,
            BigDecimal terminalGrowth
    ) {
        BigDecimal low = new BigDecimal("-0.10");
        BigDecimal high = new BigDecimal("0.30");
        if (presentValue(ownerEarnings, low, discountRate, terminalGrowth).compareTo(price) >= 0) {
            return low;
        }
        if (presentValue(ownerEarnings, high, discountRate, terminalGrowth).compareTo(price) <= 0) {
            return high;
        }
        for (int index = 0; index < 80; index++) {
            BigDecimal middle = low.add(high).divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            if (presentValue(ownerEarnings, middle, discountRate, terminalGrowth).compareTo(price) < 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low.add(high).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal presentValue(
            BigDecimal ownerEarnings,
            BigDecimal growth,
            BigDecimal discountRate,
            BigDecimal terminalGrowth
    ) {
        double cash = ownerEarnings.doubleValue();
        double growthValue = growth.doubleValue();
        double discount = discountRate.doubleValue();
        double terminal = terminalGrowth.doubleValue();
        double presentValue = 0d;
        for (int year = 1; year <= 10; year++) {
            cash *= 1d + growthValue;
            presentValue += cash / Math.pow(1d + discount, year);
        }
        double terminalCash = cash * (1d + terminal);
        presentValue += terminalCash / (discount - terminal) / Math.pow(1d + discount, 10);
        return BigDecimal.valueOf(Math.max(0d, presentValue));
    }

    private BigDecimal justifiedPb(BigDecimal roe, BigDecimal discountRate, BigDecimal growth) {
        return roe.subtract(growth)
                .divide(discountRate.subtract(growth), 8, RoundingMode.HALF_UP)
                .max(ZERO);
    }

    private BigDecimal discountPercent(BigDecimal price, BigDecimal baseValue) {
        if (!positive(price) || !positive(baseValue)) {
            return null;
        }
        return baseValue.subtract(price)
                .divide(baseValue, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
    }

    private int consecutiveDividendYears(List<EastMoneyAnnualIndicator> history) {
        int consecutive = 0;
        for (EastMoneyAnnualIndicator indicator : history) {
            if (!hasCashDividend(indicator)) {
                break;
            }
            consecutive++;
        }
        return consecutive;
    }

    private boolean hasCashDividend(EastMoneyAnnualIndicator indicator) {
        if (indicator == null) {
            return false;
        }
        if (positive(indicator.dividendYield())) {
            return true;
        }
        String description = indicator.dividendPlanDescription();
        return description != null
                && !description.isBlank()
                && !description.contains("不分配")
                && (description.contains("派") || description.contains("现金红利"));
    }

    private String modelCode(LongTermAssessmentInput input) {
        ValuationModel model = input.valuationContext() == null
                ? null : input.valuationContext().applicableModel();
        if (model == ValuationModel.FINANCIAL) {
            return "FINANCIAL";
        }
        if (model == ValuationModel.CYCLICAL) {
            return "CYCLICAL";
        }
        return "STANDARD";
    }

    private String modelLabel(String modelCode) {
        return switch (modelCode) {
            case "FINANCIAL" -> "金融PB-ROE/剩余收益代理";
            case "CYCLICAL" -> "周期标准化盈利";
            default -> "普通企业经营者收益代理";
        };
    }

    private List<EastMoneyAnnualIndicator> annualHistory(List<EastMoneyAnnualIndicator> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.reportDate() != null && item.reportDate().contains("-12-31"))
                .sorted(Comparator.comparing(EastMoneyAnnualIndicator::reportDate).reversed())
                .limit(HISTORY_YEARS)
                .toList();
    }

    private List<BigDecimal> values(
            List<EastMoneyAnnualIndicator> history,
            java.util.function.Function<EastMoneyAnnualIndicator, BigDecimal> mapper,
            boolean positiveOnly
    ) {
        return history.stream()
                .map(mapper)
                .filter(Objects::nonNull)
                .filter(value -> !positiveOnly || positive(value))
                .toList();
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal range(List<BigDecimal> values) {
        if (values == null || values.size() < 2) {
            return null;
        }
        BigDecimal min = values.stream().min(Comparator.naturalOrder()).orElse(null);
        BigDecimal max = values.stream().max(Comparator.naturalOrder()).orElse(null);
        return min == null || max == null ? null : max.subtract(min);
    }

    private BigDecimal firstPositive(List<BigDecimal> values) {
        return values.stream().filter(this::positive).findFirst().orElse(null);
    }

    private BigDecimal ratioScore(BigDecimal value, BigDecimal reference) {
        if (value == null || reference == null || reference.signum() <= 0) {
            return ZERO;
        }
        return value.divide(reference, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE).max(ZERO);
    }

    @SafeVarargs
    private final List<String> merge(List<String>... groups) {
        List<String> merged = new ArrayList<>();
        for (List<String> group : groups) {
            if (group != null) {
                merged.addAll(group);
            }
        }
        return merged.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    private BigDecimal weighted(BigDecimal score, String weight) {
        return score.multiply(new BigDecimal(weight));
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(ZERO).min(HUNDRED);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRatio(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentPointValue(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(ZERO) > 0;
    }

    private String percent(BigDecimal ratio) {
        return ratio == null ? "缺失" : percentPointValue(ratio).stripTrailingZeros().toPlainString() + "%";
    }

    private String percentPoints(BigDecimal ratio) {
        return percent(ratio);
    }

    private String multiple(BigDecimal value) {
        return value == null ? "缺失" : plain(value) + "倍";
    }

    private String plain(BigDecimal value) {
        return value == null ? "缺失" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
