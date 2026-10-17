package com.aistock.research.policy;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Website provenance is determined by the URL, never by an editable source name or article claim. */
public record PolicySourceInfo(String publisher, String authorityTier, String authorityLabel,
                               int authorityRank, String siteHost, String attributedSource) {
    private static final Map<String, String> CENTRAL_DEPARTMENTS = Map.ofEntries(
            Map.entry("miit.gov.cn", "工业和信息化部"), Map.entry("ndrc.gov.cn", "国家发展改革委"),
            Map.entry("most.gov.cn", "科学技术部"), Map.entry("mof.gov.cn", "财政部"),
            Map.entry("nea.gov.cn", "国家能源局"), Map.entry("csrc.gov.cn", "中国证监会"),
            Map.entry("mee.gov.cn", "生态环境部"), Map.entry("moa.gov.cn", "农业农村部"),
            Map.entry("mot.gov.cn", "交通运输部"), Map.entry("mofcom.gov.cn", "商务部"),
            Map.entry("nhc.gov.cn", "国家卫生健康委"), Map.entry("mohurd.gov.cn", "住房城乡建设部"),
            Map.entry("samr.gov.cn", "市场监管总局"), Map.entry("stats.gov.cn", "国家统计局"),
            Map.entry("sasac.gov.cn", "国务院国资委"), Map.entry("mct.gov.cn", "文化和旅游部"),
            Map.entry("mnr.gov.cn", "自然资源部"), Map.entry("moe.gov.cn", "教育部"),
            Map.entry("pbc.gov.cn", "中国人民银行"), Map.entry("nfra.gov.cn", "金融监管总局"),
            Map.entry("caac.gov.cn", "中国民航局"), Map.entry("cnipa.gov.cn", "国家知识产权局"),
            Map.entry("cac.gov.cn", "国家网信办"), Map.entry("nda.gov.cn", "国家数据局"));
    private static final List<String> LOCAL_GOVERNMENT_DOMAINS = List.of("beijing.gov.cn", "shanghai.gov.cn",
            "tj.gov.cn", "cq.gov.cn", "hebei.gov.cn", "shanxi.gov.cn", "nmg.gov.cn", "ln.gov.cn", "jl.gov.cn",
            "hlj.gov.cn", "jiangsu.gov.cn", "zj.gov.cn", "ah.gov.cn", "fujian.gov.cn", "jiangxi.gov.cn",
            "shandong.gov.cn", "henan.gov.cn", "hubei.gov.cn", "hunan.gov.cn", "gd.gov.cn", "gxzf.gov.cn",
            "hainan.gov.cn", "sc.gov.cn", "guizhou.gov.cn", "yn.gov.cn", "xizang.gov.cn", "shaanxi.gov.cn",
            "gansu.gov.cn", "qinghai.gov.cn", "nx.gov.cn", "xinjiang.gov.cn");

    public static PolicySourceInfo from(String url, String attributedSource) {
        String host = "";
        try {
            URI uri = URI.create(url == null ? "" : url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null) {
                host = uri.getHost().toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) { }
        String attribution = attributedSource == null || attributedSource.isBlank() ? null : attributedSource.trim();
        if (attribution != null && attribution.length() > 160) attribution = attribution.substring(0, 160);
        if (List.of("gov.cn", "www.gov.cn", "big5.www.gov.cn", "english.www.gov.cn").contains(host)) {
            return info("中国政府网", "CENTRAL_GOVERNMENT", "中央政府官网", 100, host, attribution);
        }
        for (var entry : CENTRAL_DEPARTMENTS.entrySet()) {
            if (matches(host, entry.getKey())) return info(entry.getValue(), "CENTRAL_DEPARTMENT", "中央部门官网", 95, host, attribution);
        }
        String site = host;
        if (LOCAL_GOVERNMENT_DOMAINS.stream().anyMatch(domain -> matches(site, domain))) {
            return info(host, "LOCAL_GOVERNMENT", "地方政府官网", 80, host, attribution);
        }
        if (host.endsWith(".gov.cn")) return info(host, "GOVERNMENT", "政府网站·层级待核验", 75, host, attribution);
        for (var entry : Map.of("xinhuanet.com", "新华网", "news.cn", "新华网", "people.com.cn", "人民网", "cctv.com", "央视网").entrySet()) {
            if (matches(host, entry.getKey())) return info(entry.getValue(), "MEDIA", "媒体来源", 60, host, attribution);
        }
        for (var entry : Map.of("sse.com.cn", "上海证券交易所", "szse.cn", "深圳证券交易所", "bse.cn", "北京证券交易所", "cninfo.com.cn", "巨潮资讯").entrySet()) {
            if (matches(host, entry.getKey())) return info(entry.getValue(), "DISCLOSURE", "交易所及披露平台", 85, host, attribution);
        }
        return info(host.isBlank() ? "来源待核验" : host, "OTHER", "其他来源·待核验", 40, host, attribution);
    }

    private static boolean matches(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    private static PolicySourceInfo info(String publisher, String tier, String label, int rank, String host, String attribution) {
        return new PolicySourceInfo(publisher, tier, label, rank, host, attribution);
    }
}
