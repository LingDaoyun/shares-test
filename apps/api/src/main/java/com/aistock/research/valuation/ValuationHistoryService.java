package com.aistock.research.valuation;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.financial.FinancialHistoryService;
import com.aistock.research.financial.FinancialMetricPoint;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ValuationHistoryService {

    private static final long REPORT_CACHE_SECONDS = 300;
    private static final int INDUSTRY_PEER_LIMIT = 240;

    private final CompanyService companyService;
    private final FinancialHistoryService financialHistoryService;
    private final EastMoneyClient eastMoneyClient;
    private final Map<String, CachedReport> reportCache = new ConcurrentHashMap<>();

    public ValuationHistoryService(
            CompanyService companyService,
            FinancialHistoryService financialHistoryService,
            EastMoneyClient eastMoneyClient
    ) {
        this.companyService = companyService;
        this.financialHistoryService = financialHistoryService;
        this.eastMoneyClient = eastMoneyClient;
    }

    public ValuationHistoryReport history(String symbol) {
        return history(symbol, 10);
    }

    public ValuationHistoryReport history(String symbol, int years) {
        String cacheKey = normalize(symbol) + ":" + Math.max(years, 1);
        Instant now = Instant.now();
        CachedReport cached = reportCache.get(cacheKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.report();
        }
        ValuationHistoryReport report = buildHistory(symbol, years);
        reportCache.put(cacheKey, new CachedReport(report, now.plusSeconds(REPORT_CACHE_SECONDS)));
        return report;
    }

    private ValuationHistoryReport buildHistory(String symbol, int years) {
        CompanyProfile company = companyService.getCompany(symbol);
        FinancialHistoryReport financialHistory = financialHistoryService.history(company, years);
        List<ValuationHistoryPoint> points = points(company, financialHistory, years);
        BigDecimal currentPe = company.peTtm();
        BigDecimal currentPb = positiveOrNull(company.pbRatio());
        if (currentPe == null && !points.isEmpty()) {
            currentPe = points.get(0).pe();
        }
        if (currentPb == null && !points.isEmpty()) {
            currentPb = points.get(0).pb();
        }
        BigDecimal pePercentile = percentile(currentPe, points.stream().map(ValuationHistoryPoint::pe).toList());
        BigDecimal pbPercentile = percentile(currentPb, points.stream().map(ValuationHistoryPoint::pb).toList());
        BigDecimal averagePe = average(points.stream().map(ValuationHistoryPoint::pe).toList());
        BigDecimal averagePb = average(points.stream().map(ValuationHistoryPoint::pb).toList());
        BigDecimal minPe = min(points.stream().map(ValuationHistoryPoint::pe).toList());
        BigDecimal maxPe = max(points.stream().map(ValuationHistoryPoint::pe).toList());
        BigDecimal minPb = min(points.stream().map(ValuationHistoryPoint::pb).toList());
        BigDecimal maxPb = max(points.stream().map(ValuationHistoryPoint::pb).toList());
        PeerValuationReport peerValuation = peerValuation(company, currentPe, currentPb);
        Status status = status(points.size(), pePercentile, pbPercentile);
        return new ValuationHistoryReport(
                company.symbol(),
                company.name(),
                status.code(),
                status.label(),
                points.size(),
                currentPe,
                currentPb,
                pePercentile,
                pbPercentile,
                averagePe,
                averagePb,
                minPe,
                maxPe,
                minPb,
                maxPb,
                peerValuation,
                points,
                conclusions(points, currentPe, currentPb, pePercentile, pbPercentile, peerValuation),
                dataGaps(points, years, peerValuation),
                Instant.now()
        );
    }

    private List<ValuationHistoryPoint> points(CompanyProfile company, FinancialHistoryReport financialHistory, int years) {
        if (financialHistory.points().isEmpty()) {
            return List.of();
        }
        LocalDate end = LocalDate.now();
        LocalDate begin = end.minusYears(Math.max(years, 1) + 1L);
        List<EastMoneyKLine> klines;
        try {
            klines = eastMoneyClient.fetchDailyKLines(company.symbol(), begin, end).stream()
                    .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                    .toList();
        } catch (IllegalStateException exception) {
            return List.of();
        }
        if (klines.isEmpty()) {
            return List.of();
        }
        return financialHistory.points().stream()
                .map(point -> valuationPoint(point, klines))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(ValuationHistoryPoint::reportDate).reversed())
                .limit(years)
                .toList();
    }

    private List<ValuationHistoryPoint> valuationPoint(FinancialMetricPoint financialPoint, List<EastMoneyKLine> klines) {
        LocalDate reportDate = parseDate(financialPoint.reportDate());
        if (reportDate == null) {
            return List.of();
        }
        if (reportDate.getMonthValue() != 12 || reportDate.getDayOfMonth() != 31 || reportDate.isAfter(LocalDate.now())) {
            return List.of();
        }
        EastMoneyKLine kline = latestBeforeOrAt(klines, reportDate);
        if (kline == null || kline.close() == null) {
            return List.of();
        }
        BigDecimal pe = ratio(kline.close(), financialPoint.eps());
        BigDecimal pb = ratio(kline.close(), financialPoint.bps());
        if (pe == null && pb == null) {
            return List.of();
        }
        return List.of(new ValuationHistoryPoint(
                financialPoint.reportDate(),
                kline.tradeDate().toString(),
                kline.close(),
                financialPoint.eps(),
                financialPoint.bps(),
                pe,
                pb
        ));
    }

    private EastMoneyKLine latestBeforeOrAt(List<EastMoneyKLine> klines, LocalDate date) {
        EastMoneyKLine matched = null;
        for (EastMoneyKLine kline : klines) {
            if (!kline.tradeDate().isAfter(date)) {
                matched = kline;
            } else {
                break;
            }
        }
        return matched;
    }

    private Status status(int sampleCount, BigDecimal pePercentile, BigDecimal pbPercentile) {
        if (sampleCount < 5) {
            return new Status(sampleCount == 0 ? "MISSING_VALUATION" : "PARTIAL_VALUATION", sampleCount == 0 ? "估值历史缺失" : "估值样本不足");
        }
        boolean lowPe = pePercentile != null && pePercentile.compareTo(new BigDecimal("0.35")) <= 0;
        boolean lowPb = pbPercentile != null && pbPercentile.compareTo(new BigDecimal("0.45")) <= 0;
        boolean highPe = pePercentile != null && pePercentile.compareTo(new BigDecimal("0.80")) >= 0;
        boolean highPb = pbPercentile != null && pbPercentile.compareTo(new BigDecimal("0.80")) >= 0;
        if (lowPe || lowPb) {
            return new Status("LOW_PERCENTILE", "历史低分位");
        }
        if (highPe && highPb) {
            return new Status("HIGH_PERCENTILE", "历史高分位");
        }
        return new Status("MID_PERCENTILE", "历史中位区间");
    }

    private List<String> conclusions(
            List<ValuationHistoryPoint> points,
            BigDecimal currentPe,
            BigDecimal currentPb,
            BigDecimal pePercentile,
            BigDecimal pbPercentile,
            PeerValuationReport peerValuation
    ) {
        if (points.isEmpty()) {
            return List.of("未生成年度估值样本，估值分位仍需补数据。");
        }
        List<String> conclusions = new ArrayList<>();
        conclusions.add("已按年报 EPS/BPS 与年末收盘价生成 " + points.size() + " 个年度估值样本。");
        conclusions.add("当前 PE(TTM) " + valueOrUnknown(currentPe) + "，历史年度分位 " + percentText(pePercentile) + "。");
        conclusions.add("当前 PB " + valueOrUnknown(currentPb) + "，历史年度分位 " + percentText(pbPercentile) + "。");
        if (peerValuation.peerCount() > 0) {
            conclusions.addAll(peerValuation.conclusions());
        }
        return conclusions;
    }

    private List<String> dataGaps(List<ValuationHistoryPoint> points, int years, PeerValuationReport peerValuation) {
        List<String> gaps = new ArrayList<>();
        if (points.size() < years) {
            gaps.add("年度估值样本少于目标 " + years + " 年。");
        }
        gaps.add("当前为年末估值样本，不是日频或月频 PE/PB 序列。");
        gaps.addAll(peerValuation.dataGaps());
        return gaps.stream().distinct().toList();
    }

    private PeerValuationReport peerValuation(CompanyProfile company, BigDecimal currentPe, BigDecimal currentPb) {
        List<CompanyProfile> universe;
        try {
            List<CompanyProfile> companies = companyService.listCompanies();
            universe = companies == null ? List.of() : companies;
        } catch (IllegalStateException exception) {
            universe = List.of();
        }
        List<CompanyProfile> candidates = universe.stream()
                .filter(peer -> !peer.symbol().equalsIgnoreCase(company.symbol()))
                .filter(this::hasValuation)
                .toList();
        String industry = normalize(company.industry());
        List<CompanyProfile> industryPeers = candidates.stream()
                .filter(peer -> industry != null && industry.equals(normalize(peer.industry())))
                .toList();
        if (industryPeers.size() < 3 && industry != null) {
            industryPeers = mergeProfiles(industryPeers, industryBoardPeers(company, universe, industry));
        }
        List<CompanyProfile> themePeers = candidates.stream()
                .filter(peer -> company.themeCode() != null && company.themeCode().equals(peer.themeCode()))
                .toList();
        PeerScope peerScope = peerScope(company, industryPeers, themePeers, candidates);
        List<CompanyProfile> selected = peerScope.peers();
        List<BigDecimal> peValues = selected.stream().map(CompanyProfile::peTtm).filter(this::isPositive).sorted().toList();
        List<BigDecimal> pbValues = selected.stream().map(CompanyProfile::pbRatio).filter(this::isPositive).sorted().toList();
        BigDecimal pePeerPercentile = percentile(currentPe, peValues);
        BigDecimal pbPeerPercentile = percentile(currentPb, pbValues);
        List<PeerValuationCompany> peers = selected.stream()
                .sorted(Comparator.comparing(CompanyProfile::amount, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .map(peer -> peerCompany(company, peer, peerScope.scope()))
                .toList();
        return new PeerValuationReport(
                peerScope.scope(),
                peerScope.label(),
                selected.size(),
                currentPe,
                currentPb,
                median(peValues),
                median(pbValues),
                average(peValues),
                average(pbValues),
                pePeerPercentile,
                pbPeerPercentile,
                cheaperCount(currentPe, peValues),
                cheaperCount(currentPb, pbValues),
                peers,
                peerConclusions(peerScope, selected.size(), currentPe, currentPb, pePeerPercentile, pbPeerPercentile, peValues, pbValues),
                peerDataGaps(company, peerScope, industryPeers, themePeers)
        );
    }

    private List<CompanyProfile> industryBoardPeers(CompanyProfile company, List<CompanyProfile> universe, String industry) {
        try {
            Map<String, CompanyProfile> existingBySymbol = universe.stream()
                    .collect(LinkedHashMap::new, (map, profile) -> map.put(profile.symbol(), profile), LinkedHashMap::putAll);
            return eastMoneyClient.fetchIndustryBoardConstituents(industry, INDUSTRY_PEER_LIMIT).stream()
                    .filter(quote -> !quote.symbol().equalsIgnoreCase(company.symbol()))
                    .map(quote -> peerProfileFromQuote(company, quote, existingBySymbol.get(quote.symbol()), industry))
                    .filter(this::hasValuation)
                    .toList();
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    private CompanyProfile peerProfileFromQuote(
            CompanyProfile company,
            EastMoneyQuote quote,
            CompanyProfile existing,
            String fallbackIndustry
    ) {
        if (existing != null && hasValuation(existing) && normalize(existing.industry()) != null) {
            return existing;
        }
        String industry = normalize(quote.industry()) == null ? fallbackIndustry : quote.industry();
        return new CompanyProfile(
                quote.symbol(),
                quote.name(),
                quote.market(),
                industry,
                company.themeCode(),
                company.themeRelevance(),
                quote.latestPrice(),
                quote.changePercent(),
                quote.peTtm(),
                quote.pbRatio(),
                quote.turnoverRate(),
                quote.amount(),
                quote.quoteUrl(),
                quote.sourceName() + " + 东方财富行业成分股",
                quote.fetchedAt().toString(),
                null,
                null,
                true,
                List.of("东财行业成分股：" + industry),
                List.of("同业样本仅用于估值分布，仍需主营收入和公告复核。"),
                Map.of(
                        "pe_ttm", quote.peTtm() == null ? BigDecimal.ZERO : quote.peTtm(),
                        "pb", quote.pbRatio() == null ? BigDecimal.ZERO : quote.pbRatio()
                ),
                List.of()
        );
    }

    private List<CompanyProfile> mergeProfiles(List<CompanyProfile> primary, List<CompanyProfile> secondary) {
        Map<String, CompanyProfile> merged = new LinkedHashMap<>();
        for (CompanyProfile profile : primary) {
            merged.put(profile.symbol(), profile);
        }
        for (CompanyProfile profile : secondary) {
            merged.putIfAbsent(profile.symbol(), profile);
        }
        return new ArrayList<>(merged.values());
    }

    private PeerScope peerScope(
            CompanyProfile company,
            List<CompanyProfile> industryPeers,
            List<CompanyProfile> themePeers,
            List<CompanyProfile> marketPeers
    ) {
        if (industryPeers.size() >= 3) {
            return new PeerScope("INDUSTRY", "同行业可比", industryPeers);
        }
        if (themePeers.size() >= 5) {
            return new PeerScope("THEME_FALLBACK", "同主题可比", themePeers);
        }
        if (marketPeers.size() >= 5) {
            return new PeerScope("MARKET_FALLBACK", "全市场样本兜底", marketPeers);
        }
        if (!industryPeers.isEmpty() && normalize(company.industry()) != null) {
            return new PeerScope("PARTIAL_INDUSTRY", "同行业样本不足", industryPeers);
        }
        if (!themePeers.isEmpty()) {
            return new PeerScope("PARTIAL_THEME", "同主题样本不足", themePeers);
        }
        return new PeerScope("MISSING_PEERS", "同业样本缺失", marketPeers);
    }

    private List<String> peerConclusions(
            PeerScope peerScope,
            int peerCount,
            BigDecimal currentPe,
            BigDecimal currentPb,
            BigDecimal pePeerPercentile,
            BigDecimal pbPeerPercentile,
            List<BigDecimal> peValues,
            List<BigDecimal> pbValues
    ) {
        if (peerCount == 0) {
            return List.of("未形成可用同业估值样本，无法计算行业中位数。");
        }
        return List.of(
                "已基于" + peerScope.label() + "形成 " + peerCount + " 个可比样本。",
                "同业 PE 中位数 " + valueOrUnknown(median(peValues)) + "，当前 PE(TTM) "
                        + valueOrUnknown(currentPe) + "，同业分位 " + percentText(pePeerPercentile) + "。",
                "同业 PB 中位数 " + valueOrUnknown(median(pbValues)) + "，当前 PB "
                        + valueOrUnknown(currentPb) + "，同业分位 " + percentText(pbPeerPercentile) + "。"
        );
    }

    private List<String> peerDataGaps(
            CompanyProfile company,
            PeerScope peerScope,
            List<CompanyProfile> industryPeers,
            List<CompanyProfile> themePeers
    ) {
        List<String> gaps = new ArrayList<>();
        if (normalize(company.industry()) == null) {
            gaps.add("当前公司行业字段缺失，同业比较只能按主题或全市场兜底。");
        }
        if (!"INDUSTRY".equals(peerScope.scope())) {
            gaps.add("同行业可比样本少于 3 个，当前使用 " + peerScope.label() + "。");
        }
        if (peerScope.peers().size() < 5) {
            gaps.add("可比估值样本少于 5 个，行业中位数稳定性不足。");
        }
        if (industryPeers.isEmpty() && !themePeers.isEmpty()) {
            gaps.add("实时公司池暂未覆盖足够同行业公司，需要接入完整行业成分股。");
        }
        return gaps.stream().distinct().toList();
    }

    private PeerValuationCompany peerCompany(CompanyProfile company, CompanyProfile peer, String scope) {
        String relationType = switch (scope) {
            case "INDUSTRY", "PARTIAL_INDUSTRY" -> "同行业";
            case "THEME_FALLBACK", "PARTIAL_THEME" -> "同主题";
            default -> company.market() != null && company.market().equals(peer.market()) ? "同市场" : "全市场";
        };
        return new PeerValuationCompany(
                peer.symbol(),
                peer.name(),
                peer.industry(),
                peer.themeCode(),
                peer.peTtm(),
                peer.pbRatio(),
                peer.latestPrice(),
                peer.amount(),
                relationType
        );
    }

    private boolean hasValuation(CompanyProfile company) {
        return isPositive(company.peTtm()) || isPositive(company.pbRatio());
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private int cheaperCount(BigDecimal current, List<BigDecimal> values) {
        if (current == null) {
            return 0;
        }
        return (int) values.stream()
                .filter(value -> value.compareTo(current) < 0)
                .count();
    }

    private BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).sorted().toList();
        if (present.isEmpty()) {
            return null;
        }
        int mid = present.size() / 2;
        if (present.size() % 2 == 1) {
            return present.get(mid);
        }
        return present.get(mid - 1).add(present.get(mid)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal percentile(BigDecimal current, List<BigDecimal> values) {
        List<BigDecimal> present = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .toList();
        if (current == null || present.isEmpty()) {
            return null;
        }
        long lessOrEqual = present.stream().filter(value -> value.compareTo(current) <= 0).count();
        return BigDecimal.valueOf(lessOrEqual)
                .divide(BigDecimal.valueOf(present.size()), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
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

    private BigDecimal min(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private BigDecimal max(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? null : value;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        return LocalDate.parse(value.substring(0, 10));
    }

    private String valueOrUnknown(BigDecimal value) {
        return value == null ? "待补充" : value.toString();
    }

    private String percentText(BigDecimal value) {
        if (value == null) {
            return "待补充";
        }
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private record Status(String code, String label) {
    }

    private record PeerScope(String scope, String label, List<CompanyProfile> peers) {
    }

    private record CachedReport(ValuationHistoryReport report, Instant expiresAt) {
    }
}
