package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, source-scoped archive. Changing a configured URL never reuses another source's saved articles. */
@Repository
public class GovPolicyArchiveRepository {
    public static final int MAX_SOURCE_ITEMS = 1200;
    public static final int MAX_EXCLUDED_ITEMS = 1200;
    private static final int MAX_ITEM_JSON_CHARS = 250_000;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GovPolicyArchiveRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public static String sourceKey(PolicySourceConfig source) {
        return digest(source.url().trim() + "|" + source.type().toLowerCase(Locale.ROOT));
    }

    public List<GovPolicyItem> read(String sourceKey, LocalDate cutoff, LocalDate today) {
        return jdbc.query("""
                SELECT item_json FROM gov_policy_archive
                WHERE source_key = ? AND NOT verified_exclusion
                    AND (planning_baseline OR published_on IS NULL OR published_on >= ?)
                    AND (published_on IS NULL OR published_on <= ?)
                ORDER BY planning_baseline DESC, published_on DESC NULLS LAST, article_key
                LIMIT ?
                """, (row, index) -> deserialize(row.getString(1)), sourceKey, Date.valueOf(cutoff), Date.valueOf(today), MAX_SOURCE_ITEMS);
    }

    /** Exclusions retain the verified body and date, but never appear in ordinary archive results. */
    public List<GovPolicyItem> readExcluded(String sourceKey) {
        return jdbc.query("""
                SELECT item_json FROM gov_policy_archive
                WHERE source_key = ? AND verified_exclusion
                ORDER BY last_seen_at DESC, article_key LIMIT ?
                """, (row, index) -> deserialize(row.getString(1)), sourceKey, MAX_EXCLUDED_ITEMS);
    }

    public void exclude(String sourceKey, List<GovPolicyItem> items) {
        if (items.size() > MAX_EXCLUDED_ITEMS) throw new IllegalArgumentException("政策日期核验记录超过单源容量");
        List<Object[]> rows = new ArrayList<>();
        for (GovPolicyItem item : items) {
            if (item.url() == null || item.url().isBlank() || item.bodyText() == null || item.bodyText().isBlank())
                throw new IllegalArgumentException("政策排除记录必须保留已核验正文");
            LocalDate published = publicationDay(item.publishedAt());
            rows.add(new Object[]{sourceKey, digest(item.url()), published == null ? null : Date.valueOf(published),
                    item.planningBaseline(), serialize(item)});
        }
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO gov_policy_archive (source_key, article_key, published_on, planning_baseline, item_json, verified_exclusion)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), TRUE)
                ON CONFLICT (source_key, article_key) DO UPDATE SET
                    published_on = EXCLUDED.published_on, planning_baseline = EXCLUDED.planning_baseline,
                    item_json = EXCLUDED.item_json, verified_exclusion = TRUE, last_seen_at = CURRENT_TIMESTAMP
                """, rows);
        jdbc.update("""
                DELETE FROM gov_policy_archive WHERE source_key = ? AND verified_exclusion AND article_key IN (
                    SELECT article_key FROM gov_policy_archive WHERE source_key = ? AND verified_exclusion
                    ORDER BY last_seen_at DESC, article_key OFFSET ?
                )
                """, sourceKey, sourceKey, MAX_EXCLUDED_ITEMS);
    }

    public void upsert(String sourceKey, List<GovPolicyItem> items, LocalDate cutoff, LocalDate today) {
        if (items.size() > MAX_SOURCE_ITEMS) throw new IllegalArgumentException("政策归档超过单源容量");
        Map<String, GovPolicyItem> unique = new LinkedHashMap<>();
        for (GovPolicyItem item : items) {
            LocalDate published = publicationDay(item.publishedAt());
            if (published != null && (published.isAfter(today) || !item.planningBaseline() && published.isBefore(cutoff))) continue;
            if (item.url() == null || item.url().isBlank()) continue;
            unique.put(digest(item.url()), item);
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, GovPolicyItem> entry : unique.entrySet()) {
            GovPolicyItem item = entry.getValue();
            LocalDate published = publicationDay(item.publishedAt());
            rows.add(new Object[]{sourceKey, entry.getKey(), published == null ? null : Date.valueOf(published), item.planningBaseline(), serialize(item)});
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO gov_policy_archive (source_key, article_key, published_on, planning_baseline, item_json)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (source_key, article_key) DO UPDATE SET
                    published_on = EXCLUDED.published_on, planning_baseline = EXCLUDED.planning_baseline,
                    item_json = EXCLUDED.item_json, verified_exclusion = FALSE, last_seen_at = CURRENT_TIMESTAMP
                WHERE NOT gov_policy_archive.verified_exclusion
                    OR COALESCE(EXCLUDED.item_json ->> 'bodyText', '') ~ '[^[:space:]]'
                """, rows);
        jdbc.update("""
                DELETE FROM gov_policy_archive WHERE source_key = ? AND NOT verified_exclusion
                    AND ((NOT planning_baseline AND published_on < ?) OR published_on > ?)
                """, sourceKey, Date.valueOf(cutoff), Date.valueOf(today));
        // The caller supplies a bounded balanced set, so preserve precisely that set only when trimming is necessary.
        // Never clear history on empty fetches or a temporary source outage.
        if (!unique.isEmpty()) jdbc.update("""
                DELETE FROM gov_policy_archive WHERE source_key = ? AND NOT verified_exclusion AND article_key IN (
                    SELECT article_key FROM gov_policy_archive WHERE source_key = ? AND NOT verified_exclusion
                    ORDER BY last_seen_at DESC, planning_baseline DESC, published_on DESC NULLS LAST, article_key
                    OFFSET ?
                )
                """, sourceKey, sourceKey, MAX_SOURCE_ITEMS);
    }

    public void remove(String sourceKey, Set<String> articleUrls) {
        if (articleUrls.size() > MAX_SOURCE_ITEMS) throw new IllegalArgumentException("政策归档删除超过单次上限");
        List<Object[]> keys = articleUrls.stream().map(url -> new Object[]{sourceKey, digest(url)}).toList();
        if (!keys.isEmpty()) jdbc.batchUpdate("DELETE FROM gov_policy_archive WHERE source_key = ? AND article_key = ?", keys);
    }

    private String serialize(GovPolicyItem item) {
        try {
            String json = mapper.writeValueAsString(item);
            if (json.length() > MAX_ITEM_JSON_CHARS) throw new IllegalStateException("政策归档正文超过存储上限");
            return json;
        } catch (JsonProcessingException exception) { throw new IllegalStateException("政策归档无法保存"); }
    }

    private GovPolicyItem deserialize(String json) {
        try {
            if (json.length() > MAX_ITEM_JSON_CHARS) throw new IllegalStateException("政策归档正文超过读取上限");
            return mapper.readValue(json, GovPolicyItem.class);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("政策归档无法读取"); }
    }

    private LocalDate publicationDay(String value) {
        if (value == null) return null;
        String text = value.trim().replace(' ', 'T');
        try { return OffsetDateTime.parse(text).atZoneSameInstant(SHANGHAI).toLocalDate(); }
        catch (RuntimeException ignored) { }
        try { return LocalDateTime.parse(text).toLocalDate(); } catch (RuntimeException ignored) { }
        try { return LocalDate.parse(text); } catch (RuntimeException ignored) { return null; }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("政策归档摘要不可用"); }
    }
}
