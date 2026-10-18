package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovPolicyArchivePostgresPersistenceTest {
    @Test void archiveRoundTripsAcrossRestartPreservesHistoryAndBoundsEachConfiguredSource() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "PostgreSQL integration-test credentials are required");
        String schema = "gov_archive_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
                var repository = new GovPolicyArchiveRepository(jdbc, mapper);
                LocalDate today = LocalDate.of(2026, 10, 17);
                LocalDate cutoff = today.minusDays(730);
                String key = GovPolicyArchiveRepository.sourceKey(new PolicySourceConfig("来源", "json", "https://example.gov.cn/list.json", 90));
                String otherKey = GovPolicyArchiveRepository.sourceKey(new PolicySourceConfig("来源", "json", "https://example.gov.cn/new-list.json", 90));
                GovPolicyItem historical = item("historic", "2025-01-01", false);
                GovPolicyItem recent = item("recent", "2026-10-17", false);
                GovPolicyItem plan = item("plan", "2020-01-01", true);
                GovPolicyItem boundary = item("boundary", cutoff.toString(), false);
                repository.upsert(key, List.of(historical, recent, plan, boundary,
                        item("expired", cutoff.minusDays(1).toString(), false), item("future", "2027-01-01", true)), cutoff, today);
                var restarted = new GovPolicyArchiveRepository(jdbc, mapper);
                assertThat(restarted.read(key, cutoff, today)).containsExactlyInAnyOrder(historical, recent, plan, boundary);
                assertThat(restarted.read(otherKey, cutoff, today)).isEmpty();

                // A failed/empty network refresh does not erase the saved archive.
                restarted.upsert(key, List.of(), cutoff, today);
                assertThat(restarted.read(key, cutoff, today)).hasSize(4);
                GovPolicyItem verifiedExpired = verifiedItem("known-expired", "2020-01-01");
                GovPolicyItem verifiedFuture = verifiedItem("known-future", today.plusDays(1).toString());
                restarted.exclude(key, List.of(verifiedExpired, verifiedFuture));
                var afterExclusionRestart = new GovPolicyArchiveRepository(jdbc, mapper);
                afterExclusionRestart.upsert(key, List.of(), cutoff, today);
                assertThat(afterExclusionRestart.read(key, cutoff, today)).hasSize(4)
                        .doesNotContain(verifiedExpired, verifiedFuture);
                assertThat(afterExclusionRestart.readExcluded(key)).containsExactlyInAnyOrder(verifiedExpired, verifiedFuture);
                assertThat(afterExclusionRestart.readExcluded(otherKey)).isEmpty();
                // A concurrent collector may have read before exclusion and later replay a title-only row.
                for (String body : new String[]{null, "", " \n\t"}) {
                    var staleListing = new GovPolicyItem("来源", "html", "列表重新标为今日的产业报告", verifiedExpired.url(),
                            today.toString(), 90, body, false);
                    afterExclusionRestart.upsert(key, List.of(staleListing), cutoff, today);
                    assertThat(afterExclusionRestart.readExcluded(key)).containsExactlyInAnyOrder(verifiedExpired, verifiedFuture);
                    assertThat(afterExclusionRestart.read(key, cutoff, today)).doesNotContain(staleListing);
                }
                var correctedTitle = new GovPolicyItem("来源", "json", "更正后的产业新闻标题", historical.url(), historical.publishedAt(),
                        95, "含固态电池研发与产业应用进展的完整正文", false, "工业和信息化部");
                restarted.upsert(key, List.of(correctedTitle), cutoff, today);
                assertThat(restarted.read(key, cutoff, today)).contains(correctedTitle).doesNotContain(historical);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM gov_policy_archive WHERE source_key = ? AND NOT verified_exclusion", Integer.class, key)).isEqualTo(4);
                restarted.remove(key, Set.of(recent.url()));
                assertThat(restarted.read(key, cutoff, today)).doesNotContain(recent);
                // Moving the window drops ordinary news on the next collection but retains old planning documents.
                restarted.upsert(key, List.of(), cutoff.plusDays(1), today.plusDays(1));
                assertThat(restarted.read(key, cutoff.plusDays(1), today.plusDays(1))).contains(plan).doesNotContain(boundary);
                restarted.upsert(key, List.of(verifiedFuture), cutoff.plusDays(1), today.plusDays(1));
                assertThat(restarted.read(key, cutoff.plusDays(1), today.plusDays(1))).contains(verifiedFuture);
                assertThat(restarted.readExcluded(key)).containsExactly(verifiedExpired);

                List<GovPolicyItem> full = new ArrayList<>();
                for (int index = 0; index < GovPolicyArchiveRepository.MAX_SOURCE_ITEMS; index++) {
                    full.add(item("bounded-" + index, "2026-10-17", false));
                }
                restarted.upsert(otherKey, full, cutoff, today);
                restarted.upsert(otherKey, List.of(item("one-more", "2026-10-17", false)), cutoff, today);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM gov_policy_archive WHERE source_key = ?", Integer.class, otherKey))
                        .isEqualTo(GovPolicyArchiveRepository.MAX_SOURCE_ITEMS);
                assertThat(restarted.read(otherKey, cutoff, today)).hasSize(GovPolicyArchiveRepository.MAX_SOURCE_ITEMS);
                assertThat(restarted.read(key, cutoff, today)).contains(plan, correctedTitle);
                full.add(item("over-limit", "2026-10-17", false));
                assertThatThrownBy(() -> restarted.upsert(key, full, cutoff, today)).isInstanceOf(IllegalArgumentException.class);
                List<GovPolicyItem> excludedFull = new ArrayList<>();
                for (int index = 0; index < GovPolicyArchiveRepository.MAX_EXCLUDED_ITEMS; index++) {
                    excludedFull.add(verifiedItem("excluded-" + index, "2020-01-01"));
                }
                restarted.exclude(otherKey, excludedFull);
                restarted.exclude(otherKey, List.of(verifiedItem("excluded-extra", "2020-01-01")));
                assertThat(restarted.readExcluded(otherKey)).hasSize(GovPolicyArchiveRepository.MAX_EXCLUDED_ITEMS);
                assertThat(restarted.read(otherKey, cutoff, today)).hasSize(GovPolicyArchiveRepository.MAX_SOURCE_ITEMS);
                assertThat(restarted.readExcluded(key)).containsExactly(verifiedExpired);
                excludedFull.add(verifiedItem("excluded-over-limit", "2020-01-01"));
                assertThatThrownBy(() -> restarted.exclude(key, excludedFull)).isInstanceOf(IllegalArgumentException.class);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private GovPolicyItem item(String id, String published, boolean baseline) {
        return new GovPolicyItem("来源", baseline ? "plan" : "json", "产业发展政策新闻公告", "https://example.gov.cn/" + id,
                published, 90, null, baseline);
    }

    private GovPolicyItem verifiedItem(String id, String published) {
        return new GovPolicyItem("来源", "html", "产业发展政策研究报告", "https://example.gov.cn/" + id,
                published, 90, "已核实正文的发布日期，标题列表不能覆盖此日期。", false);
    }
}
