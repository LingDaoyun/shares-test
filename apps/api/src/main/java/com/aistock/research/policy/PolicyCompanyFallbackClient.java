package com.aistock.research.policy;

import com.aistock.research.company.CompanyProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Policy-only industry samples; these are not a replacement for executable market quotes. */
@Component
public class PolicyCompanyFallbackClient {
    static final String SOURCE_NAME = "新浪行业分类备用样本";
    static final String DIRECTORY_URL = "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php";
    private static final String NODE_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData";
    private final RestClient rest;
    private final ObjectMapper mapper;

    public PolicyCompanyFallbackClient(RestClient rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    public record Result(List<CompanyProfile> companies, List<String> dataGaps) {
        public Result { companies = List.copyOf(companies); dataGaps = List.copyOf(dataGaps); }
    }
    private record Industry(String node, String name) { }

    public Result fetch(List<List<String>> industryGroups) {
        if (industryGroups.isEmpty()) return new Result(List.of(), List.of());
        Instant started = Instant.now();
        List<String> gaps = new ArrayList<>();
        Map<String, CompanyProfile> companies = new LinkedHashMap<>();
        try {
            List<Industry> directory = readDirectory(body(DIRECTORY_URL));
            List<List<Industry>> groups = industryGroups.stream().map(keywords -> directory.stream()
                    .filter(industry -> keywords.stream().anyMatch(industry.name()::contains)).toList()).toList();
            // Round-robin gives each evidence-bearing theme a chance within the 11-node budget.
            Map<String, Industry> requested = new LinkedHashMap<>();
            for (int offset = 0; offset < directory.size() && requested.size() < 11; offset++) {
                for (List<Industry> group : groups) {
                    if (offset < group.size()) requested.putIfAbsent(group.get(offset).node(), group.get(offset));
                    if (requested.size() == 11) break;
                }
            }
            long matching = groups.stream().flatMap(List::stream).map(Industry::node).distinct().count();
            if (matching > requested.size()) gaps.add("备用行业采集最多11个分类，每类最多5家公司，其余分类未采集。");
            for (Industry industry : requested.values()) {
                if (Duration.between(started, Instant.now()).toSeconds() >= 45) {
                    gaps.add("备用行业采集达到时间预算，未完成的分类暂缺。");
                    break;
                }
                String url = nodeUrl(industry.node());
                try {
                    JsonNode rows = mapper.readTree(body(url));
                    if (!rows.isArray()) throw new IllegalArgumentException("Invalid industry rows");
                    int accepted = 0;
                    for (JsonNode row : rows) {
                        CompanyProfile profile = profile(row, industry.name(), url);
                        if (profile != null) {
                            companies.putIfAbsent(profile.symbol(), profile);
                            if (++accepted == 5) break;
                        }
                    }
                    if (accepted == 0) gaps.add(industry.name() + "：备用行业未取得有效公司样本。");
                } catch (Exception exception) {
                    gaps.add(industry.name() + "：备用行业采集失败。");
                }
            }
            if (requested.isEmpty()) gaps.add("本轮主题未匹配到新浪行业分类，未补入推测公司。");
        } catch (Exception exception) {
            gaps.add("新浪备用行业目录暂不可用，未补入推测公司。");
        }
        return new Result(new ArrayList<>(companies.values()), gaps);
    }

    static String nodeUrl(String node) {
        return NODE_URL + "?node=" + node + "&page=1&num=5&sort=amount&asc=0";
    }

    private String body(String url) {
        // The shared RestClient has a 5-second connection and 12-second read timeout.
        String body = rest.get().uri(url).header("Referer", "https://finance.sina.com.cn/").retrieve().body(String.class);
        if (body == null || body.length() > 1_000_000) throw new IllegalArgumentException("Invalid response size");
        return body;
    }

    private List<Industry> readDirectory(String body) throws Exception {
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid industry directory");
        JsonNode root = mapper.readTree(body.substring(start, end + 1));
        if (!root.isObject()) throw new IllegalArgumentException("Invalid industry directory");
        List<Industry> industries = new ArrayList<>();
        root.fields().forEachRemaining(entry -> {
            String[] fields = entry.getValue().asText("").split(",", 3);
            if (entry.getKey().matches("new_[a-z0-9]{2,16}") && fields.length >= 2
                    && entry.getKey().equals(fields[0]) && !fields[1].isBlank() && fields[1].length() <= 40) {
                industries.add(new Industry(entry.getKey(), fields[1]));
            }
        });
        return industries;
    }

    private CompanyProfile profile(JsonNode row, String industry, String sourceUrl) {
        String code = row.path("code").asText("");
        String name = row.path("name").asText("").trim();
        if (!code.matches("(?:60|68|00|30|43|83|87|92)\\d{4}") || name.isBlank() || name.length() > 60 || name.contains("ST")) return null;
        String exchange = code.startsWith("6") ? "sh" : code.startsWith("0") || code.startsWith("3") ? "sz" : "bj";
        if (!row.path("symbol").asText("").equals(exchange + code)) return null;
        return new CompanyProfile(code, name, exchange.equals("sh") ? "上交所" : exchange.equals("sz") ? "深交所" : "北交所",
                industry, "UNCLASSIFIED", BigDecimal.ZERO, null, null, null, null, null, null,
                sourceUrl, SOURCE_NAME, Instant.now().toString(), null, null, false,
                List.of("新浪行业成分：" + industry), List.of("行业分类仅作研究线索，主营与分类时效待核验"), Map.of(), List.of());
    }
}
