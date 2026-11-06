package com.aistock.research.hotspot;

import com.aistock.research.company.EvidenceItem;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-proposed identities become usable only after a public quote source confirms them. */
@Component
public class HotspotCompanyClient {
    private final EastMoneyClient quotes;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public HotspotCompanyClient(EastMoneyClient quotes, ObjectMapper mapper) {
        this.quotes = quotes;
        this.mapper = mapper;
    }

    record Candidate(String symbol, String name) { }
    record Company(EastMoneyQuote quote, String industry, List<EvidenceItem> evidence) { }

    Map<String, Company> fetch(List<Candidate> candidates, List<String> gaps) {
        Map<String, EastMoneyQuote> verified = quoteIndex(candidates.stream().map(Candidate::symbol).toList(), gaps);
        Map<String, Company> result = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            EastMoneyQuote quote = verified.get(candidate.symbol());
            if (quote == null || !sameName(candidate.name(), quote.name())) continue;
            String industry = quote.industry();
            List<EvidenceItem> evidence = new ArrayList<>();
            String code = marketCode(quote.symbol());
            String page = "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/Index?type=web&code=" + code;
            try {
                String url = "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax?code=" + code;
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://emweb.securities.eastmoney.com/").GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body().length() > 2_000_000)
                    throw new IllegalStateException("公司概况不可用");
                JsonNode profile = mapper.readTree(response.body()).path("jbzl").path(0);
                if (!quote.symbol().equals(profile.path("SECURITY_CODE").asText()))
                    throw new IllegalStateException("公司概况证券代码不匹配");
                String rawIndustry = text(profile, "EM2016");
                if (rawIndustry.isBlank()) rawIndustry = text(profile, "INDUSTRYCSRC1");
                if (!rawIndustry.isBlank()) industry = rawIndustry;
                for (String field : List.of("MAIN_BUSINESS", "BUSINESS_SCOPE")) {
                    String business = text(profile, field);
                    if (!business.isBlank()) evidence.add(new EvidenceItem(
                            "公司业务资料", quote.name() + ("MAIN_BUSINESS".equals(field) ? "：主营业务" : "：经营范围"),
                            limit(business, 1800), page, 75));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("公司资料采集中断", exception);
            } catch (Exception exception) {
                gaps.add(quote.name() + "：公司业务资料暂未取得，不能据行业名称确认具体产品业务。");
            }
            if (industry != null && !industry.isBlank()) evidence.add(new EvidenceItem("行业分类",
                    quote.name() + "：行业归属", industry, page, 65));
            result.put(quote.symbol(), new Company(quote, industry, List.copyOf(evidence)));
        }
        if (result.size() < candidates.size()) gaps.add("部分模型提名的股票未通过真实行情代码、名称核对，已排除。");
        return result;
    }

    Map<String, EastMoneyQuote> quoteIndex(List<String> symbols, List<String> gaps) {
        List<String> requested = symbols.stream().filter(HotspotCompanyClient::ashare).distinct().limit(120).toList();
        if (requested.isEmpty()) return Map.of();
        List<EastMoneyQuote> fetched = List.of();
        try { fetched = quotes.fetchTencentQuotes(requested, requested.size()); }
        catch (RuntimeException ignored) { }
        Map<String, EastMoneyQuote> result = new LinkedHashMap<>();
        addQuotes(result, fetched, requested);
        List<String> missing = requested.stream().filter(symbol -> !result.containsKey(symbol)).toList();
        if (!missing.isEmpty()) {
            try { addQuotes(result, quotes.fetchEastMoneyQuotesBySymbols(missing, missing.size()), requested); }
            catch (RuntimeException ignored) { }
        }
        if (result.size() < requested.size()) gaps.add("部分股票行情暂不可用，旧报价保留原始来源时间。");
        return result;
    }

    private void addQuotes(Map<String, EastMoneyQuote> result, List<EastMoneyQuote> values, List<String> requested) {
        for (EastMoneyQuote quote : values) {
            if (quote != null && requested.contains(quote.symbol()) && quote.name() != null
                    && !quote.name().isBlank() && !quote.name().toUpperCase().contains("ST")
                    && !quote.name().contains("退")) result.put(quote.symbol(), quote);
        }
    }

    static boolean ashare(String symbol) {
        return symbol != null && symbol.matches("(?:60|68|00|30|43|83|87|88|92)\\d{4}");
    }

    static String quoteAsOf(EastMoneyQuote quote) {
        if (quote.marketTimestamp() != null) return quote.marketTimestamp().toString();
        return quote.tradeDate() == null ? null : quote.tradeDate() + "（交易日，时分未知）";
    }

    private static String marketCode(String symbol) {
        return (symbol.startsWith("6") ? "SH" : symbol.startsWith("0") || symbol.startsWith("3") ? "SZ" : "BJ") + symbol;
    }

    private boolean sameName(String left, String right) {
        return left != null && right != null && left.replaceAll("[\\s*＊]", "")
                .equalsIgnoreCase(right.replaceAll("[\\s*＊]", ""));
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return "--".equals(value) || "null".equals(value) ? "" : value;
    }

    private String limit(String value, int length) { return value.substring(0, Math.min(value.length(), length)); }
}
