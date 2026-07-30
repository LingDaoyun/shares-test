package com.aistock.research.market.context;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class LongTermCandidateContextService {

    private final EastMoneyClient eastMoneyClient;
    private final LongTermPolicyEvidenceService policyEvidenceService;
    private final LongTermCycleContextService cycleContextService;

    public LongTermCandidateContextService(
            EastMoneyClient eastMoneyClient,
            LongTermPolicyEvidenceService policyEvidenceService,
            LongTermCycleContextService cycleContextService
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.policyEvidenceService = policyEvidenceService;
        this.cycleContextService = cycleContextService;
    }

    public LongTermCandidateContext load(String symbol, String scanIndustry) {
        validateSymbol(symbol);
        List<String> dataGaps = new ArrayList<>();
        EastMoneyQuote quote = fetchQuote(symbol, dataGaps);
        String companyName = quote == null || isBlank(quote.name()) ? symbol : quote.name();
        String market = quote == null || isBlank(quote.market()) ? marketOf(symbol) : quote.market();
        String liveIndustry = quote == null ? null : quote.industry();
        if (isBlank(liveIndustry)) {
            liveIndustry = fetchBoardIndustry(symbol, dataGaps);
        }
        String industry = resolveIndustry(liveIndustry, scanIndustry, dataGaps);

        List<EastMoneyAnnualIndicator> financials = fetchFinancials(symbol, dataGaps);
        List<EastMoneyKLine> klines = fetchKLines(symbol, dataGaps);
        LongTermPolicyEvidence policyEvidence = fetchPolicyEvidence(industry, companyName, dataGaps);
        LongTermIndustryContext industryContext = cycleContextService.classifyIndustry(industry);
        LongTermCycleSnapshot cycleContext = cycleContextService.evaluate(
                symbol,
                companyName,
                industry,
                financials,
                klines,
                policyEvidence
        );
        dataGaps.addAll(industryContext.dataGaps());
        dataGaps.addAll(policyEvidence.dataGaps());
        dataGaps.addAll(cycleContext.dataGaps());

        return new LongTermCandidateContext(
                symbol,
                companyName,
                market,
                industry,
                industryContext,
                policyEvidence,
                cycleContext,
                Instant.now(),
                dataGaps.stream().filter(Objects::nonNull).distinct().toList()
        );
    }

    private EastMoneyQuote fetchQuote(String symbol, List<String> dataGaps) {
        try {
            return eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(symbol), 1)
                    .stream()
                    .filter(item -> symbol.equals(item.symbol()))
                    .findFirst()
                    .orElseGet(() -> {
                        dataGaps.add("实时行情未返回该股票");
                        return null;
                    });
        } catch (RuntimeException exception) {
            dataGaps.add("实时行情暂不可用：" + rootMessage(exception));
            return null;
        }
    }

    private String fetchBoardIndustry(String symbol, List<String> dataGaps) {
        try {
            String industry = eastMoneyClient.fetchStockBoardIndustry(symbol);
            if (isBlank(industry)) {
                dataGaps.add("服务端实时行业未确认");
            }
            return industry;
        } catch (RuntimeException exception) {
            dataGaps.add("实时行业暂不可用：" + rootMessage(exception));
            return null;
        }
    }

    private List<EastMoneyAnnualIndicator> fetchFinancials(String symbol, List<String> dataGaps) {
        try {
            return eastMoneyClient.fetchAnnualIndicatorHistory(symbol, 5);
        } catch (RuntimeException exception) {
            dataGaps.add("年度财务暂不可用：" + rootMessage(exception));
            return List.of();
        }
    }

    private List<EastMoneyKLine> fetchKLines(String symbol, List<String> dataGaps) {
        LocalDate end = LocalDate.now();
        try {
            return eastMoneyClient.fetchDailyKLines(symbol, end.minusYears(1).minusDays(45), end);
        } catch (RuntimeException exception) {
            dataGaps.add("K线暂不可用：" + rootMessage(exception));
            return List.of();
        }
    }

    private LongTermPolicyEvidence fetchPolicyEvidence(
            String industry,
            String companyName,
            List<String> dataGaps
    ) {
        try {
            return policyEvidenceService.evaluate(industry, companyName);
        } catch (RuntimeException exception) {
            String gap = "政策证据暂不可用：" + rootMessage(exception);
            dataGaps.add(gap);
            return new LongTermPolicyEvidence(List.of(), List.of(gap));
        }
    }

    private String resolveIndustry(String liveIndustry, String scanIndustry, List<String> dataGaps) {
        String normalizedLive = trimToNull(liveIndustry);
        String normalizedScan = trimToNull(scanIndustry);
        if (normalizedLive != null) {
            if (normalizedScan != null && !normalizedLive.equals(normalizedScan)) {
                dataGaps.add("扫描行业“" + normalizedScan + "”与实时行业“"
                        + normalizedLive + "”不一致，已采用实时行业");
            }
            return normalizedLive;
        }
        if (normalizedScan != null) {
            dataGaps.add("服务端实时行业未确认；扫描行业“" + normalizedScan + "”仅用于回显，未作为事实证据");
            return "行业待补";
        }
        dataGaps.add("所属行业待补");
        return "行业待补";
    }

    private String marketOf(String symbol) {
        if (symbol.startsWith("6")) {
            return "沪A";
        }
        if (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("9")) {
            return "北交所";
        }
        return "深A";
    }

    private void validateSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[034689]\\d{5}")) {
            throw new IllegalArgumentException("股票代码格式不正确");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? "未知错误"
                : current.getMessage();
    }
}
