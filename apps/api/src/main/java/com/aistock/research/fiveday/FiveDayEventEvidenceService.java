package com.aistock.research.fiveday;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.integration.cninfo.CninfoAnnouncement;
import com.aistock.research.integration.cninfo.CninfoClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.aistock.research.fiveday.FiveDayContextModels.*;

@Service
public class FiveDayEventEvidenceService {
    private static final int POLICY_LIMIT = 40;
    private static final int ANNOUNCEMENT_LIMIT = 30;
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final List<String> RISK_TERMS = List.of(
            "减持", "立案", "处罚", "诉讼", "仲裁", "停牌", "风险提示", "退市", "违约", "冻结", "警示函");
    // Exact industry keys prevent a broad parent industry from matching every subdivision.
    private static final Map<String, List<String>> INDUSTRY_ALIASES = Map.ofEntries(
            Map.entry("半导体", List.of("集成电路", "芯片")),
            Map.entry("电力", List.of("电网", "发电")),
            Map.entry("电网设备", List.of("电网设备", "输配电设备")),
            Map.entry("煤炭", List.of("煤矿")),
            Map.entry("银行", List.of("信贷", "存款", "贷款")),
            Map.entry("保险", List.of("偿付能力")),
            Map.entry("证券", List.of("券商", "证券公司")),
            Map.entry("农业", List.of("农产品", "种植", "种业")),
            Map.entry("农牧饲渔", List.of("农业", "种植", "种业", "畜牧", "养殖", "饲料", "渔业")),
            Map.entry("农林牧渔", List.of("农业", "林业", "畜牧", "养殖", "渔业")),
            Map.entry("养殖业", List.of("养殖", "生猪", "畜牧")),
            Map.entry("食品饮料", List.of("食品", "饮料")),
            Map.entry("医药", List.of("药品", "创新药")),
            Map.entry("化学制药", List.of("化学药", "原料药")),
            Map.entry("生物制品", List.of("生物药", "疫苗")),
            Map.entry("医疗器械", List.of("医疗设备", "诊断试剂")),
            Map.entry("汽车", List.of("新能源汽车")),
            Map.entry("汽车零部件", List.of("汽车配件")),
            Map.entry("软件开发", List.of("软件", "工业软件")),
            Map.entry("房地产", List.of("住房", "保障性住房")),
            Map.entry("有色金属", List.of("有色金属")),
            Map.entry("化工", List.of("石化")));

    private final GovPolicyClient policies;
    private final CninfoClient announcements;
    private final Clock clock;

    @Autowired
    public FiveDayEventEvidenceService(GovPolicyClient policies, CninfoClient announcements) {
        this(policies, announcements, Clock.systemUTC());
    }

    public FiveDayEventEvidenceService(GovPolicyClient policies, CninfoClient announcements, Clock clock) {
        this.policies = policies;
        this.announcements = announcements;
        this.clock = clock;
    }

    public PolicyBundle fetchPolicies(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        GovPolicyFetchResult response;
        try {
            response = policies.fetchLatestPoliciesWithStatus(POLICY_LIMIT);
        } catch (RuntimeException exception) {
            return new PolicyBundle("UNKNOWN", List.of(), List.of("政策源暂不可用，不能解释为没有政策风险。"));
        }
        Instant observedAt = clock.instant();
        if (response == null || response.items().isEmpty()) {
            List<String> notes = new ArrayList<>(List.of("未取得政策材料，政策覆盖为UNKNOWN，不能解释为没有政策风险。"));
            if (response != null) notes.addAll(response.failedSources());
            return new PolicyBundle("UNKNOWN", List.of(), notes);
        }
        LocalDate today = asOf.atZone(CHINA).toLocalDate();
        LocalDate start = today.minusDays(6);
        Set<String> notes = new LinkedHashSet<>(List.of("政策窗口为" + start + "至" + today
                + "（含当日共7个自然日），仅限已配置政策源；不代表全网消息覆盖。"));
        if (!response.failedSources().isEmpty()) {
            notes.add("部分政策源不可用，覆盖不完整：" + String.join("；", response.failedSources()));
        }
        boolean partial = !response.failedSources().isEmpty();
        Set<String> titles = new HashSet<>();
        Set<String> urls = new HashSet<>();
        List<Evidence> evidence = new ArrayList<>();
        for (GovPolicyItem item : response.items().stream().limit(POLICY_LIMIT).toList()) {
            if (item == null || blank(item.title()) || !validUrl(item.url())) {
                partial = true;
                notes.add("已排除标题缺失或原文链接非法的政策材料。");
                continue;
            }
            PolicyDate date = policyDate(item.publishedAt());
            if (date != null && (date.date().isAfter(today)
                    || date.instant() != null && date.instant().isAfter(asOf))) {
                partial = true;
                notes.add("已排除晚于扫描开始时点的未来日期政策。");
                continue;
            }
            if (date != null && date.date().isBefore(start)) continue;
            if (!unique(item.title(), item.url(), titles, urls)) continue;
            String interpretation = "公开政策标题线索；不证明公司主营受益，不据此增加收益分数。";
            if (date == null) {
                partial = true;
                notes.add("日期未知材料单列，不能认定为最近7日的新催化。");
                interpretation += "发布日期未知，不能作为新催化。";
            } else if (date.instant() == null) {
                interpretation += "公开时间仅精确到日，未补造时分。";
            }
            evidence.add(new Evidence("POLICY", item.title().trim(), item.url().trim(), item.source(),
                    item.publishedAt(), observedAt, interpretation, date == null ? "DATE_UNKNOWN" : "RECENT"));
        }
        if (evidence.isEmpty()) notes.add("本批材料未留下可用的近期政策或日期未知线索，不代表没有相关政策。");
        return new PolicyBundle(partial ? "PARTIAL" : "READY", evidence, List.copyOf(notes));
    }

    public EventReview review(String symbol, String name, String industry, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (symbol == null || !symbol.matches("\\d{6}")) {
            return new EventReview("UNKNOWN", List.of(), List.of("证券代码无法确认，未请求公司公告。"));
        }
        List<CninfoAnnouncement> rows;
        try {
            rows = announcements.fetchAnnouncements(company(symbol, name, industry, asOf), ANNOUNCEMENT_LIMIT);
        } catch (RuntimeException exception) {
            return new EventReview("UNKNOWN", List.of(), List.of("巨潮公告源暂不可用；不能判断最近7日标题风险。"));
        }
        Instant observedAt = clock.instant();
        if (rows == null || rows.isEmpty()) {
            return new EventReview("UNKNOWN", List.of(), List.of("未取得可审阅公告，不能证明最近7日窗口完整。"));
        }
        LocalDate start = asOf.atZone(CHINA).toLocalDate().minusDays(6);
        Set<String> notes = new LinkedHashSet<>(List.of("仅审阅巨潮最新最多30条中的最近7个自然日公告标题；"
                + "NO_TITLE_RISK仅指未发现所列标题风险词，不代表已核实公司无风险，也不覆盖全网新闻或传闻。"));
        List<Evidence> evidence = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        Set<String> urls = new HashSet<>();
        boolean unknown = false;
        boolean titleRisk = false;
        LocalDate boundary = null;
        for (CninfoAnnouncement row : rows.stream().limit(ANNOUNCEMENT_LIMIT).toList()) {
            if (row == null || !symbol.equals(row.symbol())) {
                unknown = true;
                notes.add("UNKNOWN：存在证券代码不匹配的公告，已排除，不能证明本证券覆盖完整。");
                continue;
            }
            if (row.announcementTime() <= 0) {
                unknown = true;
                notes.add("UNKNOWN：存在公告日期无法确认的材料（时间戳缺失或为零），已排除。");
                continue;
            }
            Instant publishedAt = Instant.ofEpochMilli(row.announcementTime());
            if (publishedAt.isAfter(asOf)) {
                unknown = true;
                notes.add("UNKNOWN：存在未来公告时间，已排除，不能据此晋级。");
                continue;
            }
            String url = announcementUrl(row);
            if (blank(row.title()) || url == null) {
                unknown = true;
                notes.add("UNKNOWN：存在标题缺失或原文链接非法的公告，已排除。");
                continue;
            }
            LocalDate date = publishedAt.atZone(CHINA).toLocalDate();
            if (date.isBefore(start)) {
                if (boundary == null || date.isBefore(boundary)) boundary = date;
                continue;
            }
            List<String> matched = RISK_TERMS.stream().filter(row.title()::contains).toList();
            titleRisk |= !matched.isEmpty();
            boolean duplicate = !unique(row.title(), url, titles, urls);
            if (duplicate) {
                boolean conflictingTitle = evidence.stream().anyMatch(prior ->
                        urlKey(prior.url()).equals(urlKey(url))
                                && !titleKey(prior.title()).equals(titleKey(row.title())));
                if (conflictingTitle) {
                    unknown = true;
                    notes.add("UNKNOWN：重复公告链接对应不同标题，覆盖存在冲突；保留其中的风险标题线索。");
                }
                boolean riskAlreadyRetained = evidence.stream().anyMatch(prior ->
                        titleKey(prior.title()).equals(titleKey(row.title()))
                                && RISK_TERMS.stream().anyMatch(prior.title()::contains));
                if (matched.isEmpty() || riskAlreadyRetained) continue;
            }
            String interpretation = matched.isEmpty()
                    ? "本公告标题未命中所列风险词；仍需阅读原文，不代表已核实无风险。"
                    : "标题命中" + String.join("、", matched) + "，仅为需阅读原文的风险线索，不代表已证实利空。";
            if (duplicate) interpretation += "重复材料中的风险标题已保留，不能被无风险标题覆盖。";
            evidence.add(new Evidence("ANNOUNCEMENT", row.title().trim(), url, "巨潮资讯", publishedAt.toString(),
                    observedAt, interpretation, "RECENT"));
        }
        if (boundary == null) {
            unknown = true;
            notes.add(rows.size() >= ANNOUNCEMENT_LIMIT
                    ? "UNKNOWN / WINDOW_LIMITED：最新30条尚未延伸到7日窗口之前，窗口可能被截断。"
                    : "UNKNOWN / WINDOW_LIMITED：已请求最新一页30条，但返回不足30条且未延伸到7日窗口之前；接口未提供总条数，不能证明覆盖完整。");
        } else {
            notes.add("本证券合法公告已延伸至" + boundary + "，早于窗口起点" + start + "；旧公告不作为本次新催化或近期风险线索。");
        }
        return new EventReview(titleRisk ? "TITLE_RISK" : unknown ? "UNKNOWN" : "NO_TITLE_RISK", evidence, List.copyOf(notes));
    }

    public List<Evidence> relatedPolicies(String industry, PolicyBundle policies) {
        if (blank(industry) || policies == null) return List.of();
        String key = industry.trim().replaceAll("\\s+", "");
        Set<String> keywords = new LinkedHashSet<>(INDUSTRY_ALIASES.getOrDefault(key, List.of()));
        keywords.add(key);
        return policies.evidence().stream()
                .filter(row -> "RECENT".equals(row.temporalStatus()) || "DATE_UNKNOWN".equals(row.temporalStatus()))
                .filter(row -> !blank(row.title()) && validUrl(row.url()))
                .filter(row -> keywords.stream().anyMatch(row.title()::contains))
                .map(row -> new Evidence(row.kind(), row.title(), row.url(), row.source(), row.publishedAt(), row.observedAt(),
                        row.interpretation() + " 行业关键词关联："
                                + String.join("、", keywords.stream().filter(row.title()::contains).toList())
                                + "；不证明公司主营受益。", row.temporalStatus()))
                .toList();
    }

    private String announcementUrl(CninfoAnnouncement row) {
        String url = null;
        if (!blank(row.adjunctUrl())) {
            try {
                URI adjunct = URI.create(row.adjunctUrl().trim());
                if (adjunct.getScheme() == null && !row.adjunctUrl().startsWith("//") || validUrl(row.adjunctUrl())) {
                    url = announcements.downloadUrl(row.adjunctUrl().trim());
                }
            } catch (IllegalArgumentException ignored) { }
        }
        if (validUrl(url)) return url.trim();
        if (blank(row.announcementId()) || blank(row.orgId())) return null;
        url = announcements.disclosureUrl(row.announcementId(), row.orgId(), row.symbol(), row.announcementTime());
        return validUrl(url) ? url.trim() : null;
    }

    private PolicyDate policyDate(String value) {
        if (blank(value)) return null;
        String text = value.trim();
        try {
            return new PolicyDate(LocalDate.parse(text), null);
        } catch (DateTimeParseException ignored) { }
        try {
            Instant instant = OffsetDateTime.parse(text).toInstant();
            return new PolicyDate(instant.atZone(CHINA).toLocalDate(), instant);
        } catch (DateTimeParseException ignored) { }
        try {
            Instant instant = LocalDateTime.parse(text.replace(' ', 'T')).atZone(CHINA).toInstant();
            return new PolicyDate(instant.atZone(CHINA).toLocalDate(), instant);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean unique(String title, String url, Set<String> titles, Set<String> urls) {
        String titleKey = titleKey(title);
        String urlKey = urlKey(url);
        if (titles.contains(titleKey) || urls.contains(urlKey)) return false;
        titles.add(titleKey);
        urls.add(urlKey);
        return true;
    }

    private String titleKey(String title) { return title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    private String urlKey(String url) { return url.trim().split("#", 2)[0]; }

    private boolean validUrl(String value) {
        if (blank(value)) return false;
        try {
            URI uri = URI.create(value.trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private CompanyProfile company(String symbol, String name, String industry, Instant asOf) {
        return new CompanyProfile(symbol, name, null, industry, null, null, null, null, null, null, null, null,
                null, "巨潮资讯", asOf.toString(), null, null, true, List.of(), List.of(), Map.of(), List.of());
    }

    private record PolicyDate(LocalDate date, Instant instant) { }
}
