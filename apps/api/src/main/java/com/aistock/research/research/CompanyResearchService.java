package com.aistock.research.research;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.filing.FilingEvidenceProvider;
import com.aistock.research.filing.FilingEvidenceSummary;
import com.aistock.research.quality.RecommendationQuality;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompanyResearchService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final FilingEvidenceProvider filingEvidenceProvider;

    public CompanyResearchService(FilingEvidenceProvider filingEvidenceProvider) {
        this.filingEvidenceProvider = filingEvidenceProvider;
    }

    public CompanyResearchView analyze(CompanyProfile company) {
        FilingEvidenceSummary filingEvidence = filingEvidenceProvider.summarize(company);
        List<DimensionScore> dimensions = List.of(
                trendScore(company),
                qualityScore(company),
                moatScore(company, filingEvidence),
                valuationScore(company),
                riskScore(company, filingEvidence)
        );
        List<String> hardBlocks = hardBlocks(company, filingEvidence);
        BigDecimal overall = weightedOverall(dimensions, hardBlocks);
        StageDecision stage = stage(overall, dimensions, hardBlocks);
        return new CompanyResearchView(
                company,
                overall,
                stage.code(),
                stage.label(),
                stage.reason(),
                dimensions,
                evidenceTiers(company, filingEvidence),
                hardBlocks,
                nextActions(company, stage, dimensions, filingEvidence),
                dataGaps(company, filingEvidence),
                sourcePlan(),
                filingEvidence,
                Instant.now()
        );
    }

    private DimensionScore trendScore(CompanyProfile company) {
        BigDecimal theme = defaulted(company.themeRelevance());
        int evidenceBonus = company.evidence().stream()
                .filter(item -> "主题映射".equals(item.sourceType()))
                .mapToInt(EvidenceItem::confidence)
                .max()
                .orElse(45);
        BigDecimal score = clamp(theme.multiply(new BigDecimal("0.72"))
                .add(BigDecimal.valueOf(evidenceBonus).multiply(new BigDecimal("0.28"))));
        return new DimensionScore(
                "TREND",
                "趋势匹配",
                score,
                score.compareTo(new BigDecimal("70")) >= 0 ? "政策/产业主题匹配较强" : "主题仍需主营收入和公告验证",
                evidenceRefs(company, "主题映射", "实时行情"),
                List.of("核查主营收入中与政策主题直接相关的业务占比", "跟踪财政预算、招投标和订单是否进入公司公告")
        );
    }

    private DimensionScore qualityScore(CompanyProfile company) {
        Map<String, BigDecimal> factors = company.factors();
        BigDecimal roe = percentFactor(factors.get("roe_annual"), "0.15");
        BigDecimal cash = boundedLinear(factors.get("operating_cash_flow_per_share"), "0.00", "2.00");
        BigDecimal growth = percentFactor(factors.get("revenue_growth"), "0.25");
        BigDecimal margin = percentFactor(factors.get("gross_margin"), "0.45");
        int present = countPresent(factors, "roe_annual", "operating_cash_flow_per_share", "revenue_growth", "gross_margin");
        BigDecimal score = average(List.of(roe, cash, growth, margin));
        if (present < 3) {
            score = score.multiply(new BigDecimal("0.72")).setScale(2, RoundingMode.HALF_UP);
        }
        return new DimensionScore(
                "QUALITY",
                "财务质量",
                clamp(score),
                present >= 3 ? "已有年报指标可做初步质量判断" : "年报指标不足，质量维度必须人工复核",
                evidenceRefs(company, "年报指标"),
                List.of("补齐最近 5-10 年 ROE、毛利率、现金流和营收增速", "识别一次性收益、会计政策变化和研发资本化比例")
        );
    }

    private DimensionScore moatScore(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        int assetCount = company.coreAssets() == null ? 0 : company.coreAssets().size();
        int evidenceCount = company.evidence() == null ? 0 : company.evidence().size();
        BigDecimal score = BigDecimal.valueOf(42)
                .add(BigDecimal.valueOf(Math.min(assetCount, 4) * 9L))
                .add(BigDecimal.valueOf(Math.min(evidenceCount, 5) * 4L))
                .add(BigDecimal.valueOf(Math.min(filingEvidence.moatSignals().size(), 4) * 6L));
        if ("LIVE".equals(filingEvidence.status()) && !filingEvidence.moatSignals().isEmpty()) {
            score = score.add(new BigDecimal("4"));
        }
        if (company.industry() != null && !company.industry().isBlank()) {
            score = score.add(new BigDecimal("8"));
        }
        return new DimensionScore(
                "MOAT",
                "核心壁垒",
                clamp(score),
                score.compareTo(new BigDecimal("70")) >= 0 ? "已有可描述的核心资产线索" : "壁垒仍停留在线索层，需要公告和业务数据确认",
                mergeRefs(company.coreAssets(), filingEvidence.moatSignals()),
                List.of("读取年报中的核心产品、客户认证、专利、产能和研发项目", "核查前五大客户、供应商集中度和替代风险")
        );
    }

    private DimensionScore valuationScore(CompanyProfile company) {
        BigDecimal pe = company.peTtm();
        BigDecimal pb = company.pbRatio();
        BigDecimal peScore = pe == null || pe.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("35")
                : reverseLinear(pe, "15", "80");
        BigDecimal pbScore = pb == null || pb.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("45")
                : reverseLinear(pb, "1.2", "8.0");
        BigDecimal score = peScore.multiply(new BigDecimal("0.62"))
                .add(pbScore.multiply(new BigDecimal("0.38")))
                .setScale(2, RoundingMode.HALF_UP);
        return new DimensionScore(
                "VALUATION",
                "估值安全边际",
                clamp(score),
                score.compareTo(new BigDecimal("65")) >= 0 ? "估值暂未明显透支" : "估值偏高或盈利口径不足，适合等待价格",
                evidenceRefs(company, "实时行情"),
                List.of("计算历史 3/5/10 年 PE、PB、PS 分位", "用自由现金流收益率和行业可比公司验证安全边际")
        );
    }

    private DimensionScore riskScore(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        BigDecimal score = HUNDRED;
        if (company.name().contains("ST")) {
            score = score.subtract(new BigDecimal("45"));
        }
        if (company.peTtm() == null || company.peTtm().compareTo(BigDecimal.ZERO) <= 0) {
            score = score.subtract(new BigDecimal("18"));
        }
        if (!RecommendationQuality.hasSufficientLiquidity(company.amount())) {
            score = score.subtract(new BigDecimal("18"));
        }
        if (company.financialReportDate() == null) {
            score = score.subtract(new BigDecimal("12"));
        }
        int extraRisks = company.risks() == null ? 0 : company.risks().size();
        score = score.subtract(BigDecimal.valueOf(Math.min(extraRisks * 3L, 18)));
        score = score.subtract(BigDecimal.valueOf(Math.min(filingEvidence.riskSignals().size() * 7L, 28)));
        if ("MISSING".equals(filingEvidence.status())) {
            score = score.subtract(new BigDecimal("8"));
        }
        return new DimensionScore(
                "RISK",
                "风险排雷",
                clamp(score),
                score.compareTo(new BigDecimal("70")) >= 0 ? "暂未触发硬性风险" : "存在需要优先排查的风险项",
                mergeRefs(company.risks(), filingEvidence.riskSignals()),
                List.of("抓取监管处罚、问询函、诉讼、质押、减持和审计意见", "排查商誉减值、应收账款异常和关联交易")
        );
    }

    private List<EvidenceTier> evidenceTiers(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        grouped.put("POLICY", new ArrayList<>());
        grouped.put("MARKET", new ArrayList<>());
        grouped.put("FILING", new ArrayList<>());
        grouped.put("VALIDATION", new ArrayList<>());
        for (EvidenceItem item : company.evidence()) {
            String ref = item.sourceType() + " / " + item.sourceTitle();
            if ("主题映射".equals(item.sourceType())) {
                grouped.get("POLICY").add(ref);
            } else if ("实时行情".equals(item.sourceType())) {
                grouped.get("MARKET").add(ref);
            } else if ("年报指标".equals(item.sourceType())) {
                grouped.get("FILING").add(ref);
            } else {
                grouped.get("VALIDATION").add(ref);
            }
        }
        filingEvidence.documents().forEach(document -> {
            String ref = document.source() + " / " + document.title();
            grouped.get("FILING").add(ref);
            if ("业务验证".equals(document.category())) {
                grouped.get("VALIDATION").add(ref);
            }
        });
        return List.of(
                new EvidenceTier("POLICY", "政策/主题线索", grouped.get("POLICY").isEmpty() ? 45 : 58, grouped.get("POLICY")),
                new EvidenceTier("MARKET", "行情与估值证据", grouped.get("MARKET").isEmpty() ? 35 : 62, grouped.get("MARKET")),
                new EvidenceTier("FILING", "公告/年报证据", filingStrength(company, filingEvidence), grouped.get("FILING")),
                new EvidenceTier("VALIDATION", "订单/招投标/财务兑现", validationStrength(filingEvidence, grouped.get("VALIDATION")), grouped.get("VALIDATION"))
        );
    }

    private BigDecimal weightedOverall(List<DimensionScore> dimensions, List<String> hardBlocks) {
        Map<String, BigDecimal> score = new LinkedHashMap<>();
        for (DimensionScore dimension : dimensions) {
            score.put(dimension.code(), dimension.score());
        }
        BigDecimal overall = score.get("TREND").multiply(new BigDecimal("0.22"))
                .add(score.get("QUALITY").multiply(new BigDecimal("0.24")))
                .add(score.get("MOAT").multiply(new BigDecimal("0.18")))
                .add(score.get("VALUATION").multiply(new BigDecimal("0.18")))
                .add(score.get("RISK").multiply(new BigDecimal("0.18")))
                .setScale(2, RoundingMode.HALF_UP);
        if (!hardBlocks.isEmpty()) {
            overall = overall.min(new BigDecimal("59.00"));
        }
        return clamp(overall);
    }

    private StageDecision stage(BigDecimal overall, List<DimensionScore> dimensions, List<String> hardBlocks) {
        if (!hardBlocks.isEmpty()) {
            return new StageDecision("RISK_REVIEW", "风险复核", String.join("；", hardBlocks));
        }
        BigDecimal trend = dimension(dimensions, "TREND");
        BigDecimal quality = dimension(dimensions, "QUALITY");
        BigDecimal valuation = dimension(dimensions, "VALUATION");
        BigDecimal risk = dimension(dimensions, "RISK");
        if (overall.compareTo(new BigDecimal("78")) >= 0
                && trend.compareTo(new BigDecimal("65")) >= 0
                && quality.compareTo(new BigDecimal("65")) >= 0
                && valuation.compareTo(new BigDecimal("65")) >= 0
                && risk.compareTo(new BigDecimal("70")) >= 0) {
            return new StageDecision("VALUATION_WATCH", "估值观察", "趋势、质量和估值均达到观察门槛");
        }
        if (trend.compareTo(new BigDecimal("65")) >= 0
                && quality.compareTo(new BigDecimal("60")) >= 0
                && risk.compareTo(new BigDecimal("70")) >= 0) {
            return new StageDecision("WAIT_FOR_PRICE", "等待价格", "趋势和质量可继续跟踪，但估值或安全边际不足");
        }
        if (trend.compareTo(new BigDecimal("58")) >= 0 && risk.compareTo(new BigDecimal("55")) >= 0) {
            return new StageDecision("EVIDENCE_BUILDING", "证据验证", "需要更多公告、订单和财务兑现证据");
        }
        return new StageDecision("WATCH_SAMPLE", "样本观察", "当前只适合作为样本跟踪");
    }

    private List<String> hardBlocks(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        List<String> blocks = new ArrayList<>();
        if (company.name().contains("ST")) {
            blocks.add("名称包含 ST，默认进入风险复核");
        }
        BigDecimal st = company.factors().get("st_flag");
        if (st != null && st.compareTo(BigDecimal.ONE) >= 0) {
            blocks.add("ST 风险因子触发");
        }
        if (!RecommendationQuality.hasSufficientLiquidity(company.amount())) {
            blocks.add(RecommendationQuality.liquidityRiskText());
        }
        if (filingEvidence.riskSignals().stream().anyMatch(signal -> signal.contains("退市") || signal.contains("立案"))) {
            blocks.add("公告标题出现退市或立案风险线索");
        }
        return blocks;
    }

    private List<String> nextActions(
            CompanyProfile company,
            StageDecision stage,
            List<DimensionScore> dimensions,
            FilingEvidenceSummary filingEvidence
    ) {
        List<String> actions = new ArrayList<>();
        actions.add("补齐最近 5-10 年财务因子和历史估值分位");
        if ("LIVE".equals(filingEvidence.status())) {
            actions.add("下载并阅读最新公告 PDF，确认标题线索是否对应真实业务和财务兑现");
        } else {
            actions.add("补齐巨潮、上交所、深交所、北交所公告源并做风险事件抽取");
        }
        if (!filingEvidence.riskSignals().isEmpty()) {
            actions.add("逐条复核风险公告，判断是否触发剔除、降权或人工复核");
        }
        if ("WAIT_FOR_PRICE".equals(stage.code())) {
            actions.add("设置估值分位和目标安全边际提醒");
        }
        if (dimension(dimensions, "QUALITY").compareTo(new BigDecimal("60")) < 0) {
            actions.add("优先读取年报，确认 ROE、现金流、毛利率和主营业务质量");
        }
        if (company.quoteUrl() != null) {
            actions.add("复核行情源：" + company.quoteUrl());
        }
        return actions;
    }

    private List<String> dataGaps(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        List<String> gaps = new ArrayList<>();
        if (company.financialReportDate() == null) {
            gaps.add("缺少最近年度年报指标匹配");
        }
        gaps.add("缺少主营收入按产品/地区/客户拆分");
        gaps.addAll(filingEvidence.dataGaps());
        gaps.add("缺少历史估值分位和同业比较");
        gaps.add("缺少回测结果验证当前规则有效性");
        return gaps.stream().distinct().toList();
    }

    private List<String> sourcePlan() {
        return List.of(
                "巨潮资讯：年报、临时公告、问询函、监管措施、股权质押、股东增减持",
                "上交所/深交所/北交所：公告、监管、纪律处分和退市风险",
                "国家统计局/财政部/公共资源交易平台：产业需求、预算和招投标验证",
                "行情与财务源：历史估值分位、现金流、ROE、毛利率和分红"
        );
    }

    private BigDecimal dimension(List<DimensionScore> dimensions, String code) {
        return dimensions.stream()
                .filter(dimension -> code.equals(dimension.code()))
                .map(DimensionScore::score)
                .findFirst()
                .orElse(ZERO);
    }

    private List<String> evidenceRefs(CompanyProfile company, String... sourceTypes) {
        List<String> types = List.of(sourceTypes);
        return company.evidence().stream()
                .filter(item -> types.contains(item.sourceType()))
                .map(item -> item.sourceType() + " / " + item.sourceTitle())
                .toList();
    }

    private List<String> mergeRefs(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.stream().distinct().toList();
    }

    private int filingStrength(CompanyProfile company, FilingEvidenceSummary filingEvidence) {
        int base = switch (filingEvidence.status()) {
            case "LIVE" -> 68;
            case "FALLBACK" -> 52;
            default -> 30;
        };
        int score = base + Math.min(filingEvidence.totalDocuments() * 2, 18);
        if (company.financialReportDate() != null) {
            score = Math.max(score, 70);
        }
        return Math.min(score, 90);
    }

    private int validationStrength(FilingEvidenceSummary filingEvidence, List<String> refs) {
        if (refs.isEmpty() && filingEvidence.validationSignals().isEmpty()) {
            return 25;
        }
        int score = "LIVE".equals(filingEvidence.status()) ? 66 : 54;
        return Math.min(86, score + Math.min(filingEvidence.validationSignals().size() * 5, 20));
    }

    private int countPresent(Map<String, BigDecimal> factors, String... keys) {
        int count = 0;
        for (String key : keys) {
            if (factors.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal average(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentFactor(BigDecimal value, String target) {
        if (value == null) {
            return new BigDecimal("45.00");
        }
        return boundedLinear(value, "0.00", target);
    }

    private BigDecimal boundedLinear(BigDecimal value, String low, String high) {
        if (value == null) {
            return new BigDecimal("45.00");
        }
        BigDecimal min = new BigDecimal(low);
        BigDecimal max = new BigDecimal(high);
        if (value.compareTo(min) <= 0) {
            return new BigDecimal("35.00");
        }
        if (value.compareTo(max) >= 0) {
            return HUNDRED;
        }
        return value.subtract(min)
                .multiply(HUNDRED.subtract(new BigDecimal("35.00")))
                .divide(max.subtract(min), 2, RoundingMode.HALF_UP)
                .add(new BigDecimal("35.00"));
    }

    private BigDecimal reverseLinear(BigDecimal value, String low, String high) {
        BigDecimal min = new BigDecimal(low);
        BigDecimal max = new BigDecimal(high);
        if (value.compareTo(min) <= 0) {
            return new BigDecimal("92.00");
        }
        if (value.compareTo(max) >= 0) {
            return new BigDecimal("30.00");
        }
        return new BigDecimal("92.00").subtract(value.subtract(min)
                .multiply(new BigDecimal("62.00"))
                .divide(max.subtract(min), 2, RoundingMode.HALF_UP));
    }

    private BigDecimal defaulted(BigDecimal value) {
        return value == null ? new BigDecimal("50.00") : value;
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

    private record StageDecision(String code, String label, String reason) {
    }
}
