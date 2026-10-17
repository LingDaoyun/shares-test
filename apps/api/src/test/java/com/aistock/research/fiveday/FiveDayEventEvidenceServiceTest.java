package com.aistock.research.fiveday;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.integration.cninfo.CninfoAnnouncement;
import com.aistock.research.integration.cninfo.CninfoClient;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyFetchResult;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayEventEvidenceServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-09-08T02:00:00Z");
    private static final Instant OBSERVED = AS_OF.plusSeconds(2);
    private final GovPolicyClient policies = mock(GovPolicyClient.class);
    private final CninfoClient announcements = mock(CninfoClient.class);
    private final Clock acquisitionClock = mock(Clock.class);
    private final FiveDayEventEvidenceService service = new FiveDayEventEvidenceService(policies, announcements, acquisitionClock);

    @BeforeEach
    void configureLocalProviderDoubles() {
        when(acquisitionClock.instant()).thenReturn(OBSERVED);
        when(announcements.downloadUrl(any())).thenCallRealMethod();
        when(announcements.disclosureUrl(any(), any(), any(), anyLong())).thenCallRealMethod();
    }

    @Test
    void policiesKeepOnlySevenCalendarDaysAndUnknownDatesWithOriginalPrecision() {
        policyRows(List.of(
                policy("农业产业扶持政策", "https://policy.example/recent", "2026-09-02"),
                policy("农业供给通知", "https://policy.example/today", "2026-09-08"),
                policy("农业旧规划", "https://policy.example/old", "2026-09-01"),
                policy("农业未知日期意见", "https://policy.example/unknown", "日期未公开"),
                policy("农业未来规划", "https://policy.example/future", "2026-09-09"),
                policy("农业当日未到时点通知", "https://policy.example/later", "2026-09-08T03:00:00Z")));

        PolicyBundle result = service.fetchPolicies(AS_OF);

        assertThat(result.evidence()).extracting(Evidence::title)
                .containsExactly("农业产业扶持政策", "农业供给通知", "农业未知日期意见");
        assertThat(result.evidence()).allMatch(row -> OBSERVED.equals(row.observedAt()));
        assertThat(result.evidence().get(0).publishedAt()).isEqualTo("2026-09-02");
        assertThat(result.evidence().get(0).interpretation()).contains("仅精确到日");
        assertThat(result.evidence().get(2).temporalStatus()).isEqualTo("DATE_UNKNOWN");
        assertThat(result.evidence().get(2).interpretation()).contains("日期", "不能", "催化");
        assertThat(result.notes()).anyMatch(note -> note.contains("日期未知"));
        verify(policies).fetchLatestPoliciesWithStatus(40);
    }

    @Test
    void duplicateTitlesOrUrlsAndInvalidPolicyLinksCannotCreateNewEvidence() {
        policyRows(List.of(
                policy("半导体 政策", "https://policy.example/a", "2026-09-07"),
                policy("半导体政策", "https://policy.example/b", "2026-09-07"),
                policy("集成电路实施细则", "https://policy.example/a#section", "2026-09-08"),
                policy("银行贷款政策", "javascript:alert(1)", "2026-09-08"),
                policy("保险政策", "https:///missing-host", "2026-09-08")));

        PolicyBundle result = service.fetchPolicies(AS_OF);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).title()).isEqualTo("半导体 政策");
    }

    @Test
    void oldDuplicateDoesNotSuppressARecentPolicyAndFetchRemainsBounded() {
        List<GovPolicyItem> rows = new ArrayList<>();
        rows.add(policy("半导体支持政策", "https://policy.example/reissued", "2026-08-01"));
        rows.add(policy("半导体支持政策", "https://policy.example/reissued", "2026-09-07"));
        IntStream.range(0, 45).forEach(i -> rows.add(policy("农业政策" + i,
                "https://policy.example/p" + i, "2026-09-07")));
        policyRows(rows);

        PolicyBundle result = service.fetchPolicies(AS_OF);

        assertThat(result.evidence()).hasSizeLessThanOrEqualTo(40);
        assertThat(result.evidence()).anyMatch(row -> row.title().equals("半导体支持政策"));
    }

    @Test
    void policySourceFailuresRemainUnknownOrPartialAndNeverMeanNoRisk() {
        when(policies.fetchLatestPoliciesWithStatus(40)).thenThrow(new IllegalStateException("offline"));
        assertThat(service.fetchPolicies(AS_OF).status()).isEqualTo("UNKNOWN");

        doReturn(new GovPolicyFetchResult(
                List.of(policy("银行贷款政策", "https://policy.example/a", "2026-09-08")), List.of("政策源甲不可用")))
                .when(policies).fetchLatestPoliciesWithStatus(40);
        PolicyBundle partial = service.fetchPolicies(AS_OF);
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.evidence()).hasSize(1);
        assertThat(partial.notes()).anyMatch(note -> note.contains("政策源甲不可用"));
    }

    @Test
    void emptyPolicyResultIsUnknownAndObservedTimesFollowEachAcquisition() {
        policyRows(List.of());
        assertThat(service.fetchPolicies(AS_OF).status()).isEqualTo("UNKNOWN");
        policyRows(List.of(policy("半导体政策", "https://policy.example/a", "2026-09-08")));
        PolicyBundle first = service.fetchPolicies(AS_OF);
        when(acquisitionClock.instant()).thenReturn(AS_OF.plusSeconds(600));
        PolicyBundle later = service.fetchPolicies(AS_OF);
        assertThat(first.evidence()).hasSize(1);
        assertThat(later.evidence()).hasSize(1);
        assertThat(first.evidence().get(0).observedAt()).isEqualTo(OBSERVED);
        assertThat(later.evidence().get(0).observedAt()).isEqualTo(AS_OF.plusSeconds(600));
    }

    @Test
    void observationTimeIsCapturedAfterResponsesFinishAcrossTheEntryCutoff() {
        Instant started = Instant.parse("2026-09-08T01:14:59Z");
        Instant completed = Instant.parse("2026-09-08T01:15:03Z");
        when(acquisitionClock.instant()).thenReturn(started);
        when(policies.fetchLatestPoliciesWithStatus(40)).thenAnswer(call -> {
            when(acquisitionClock.instant()).thenReturn(completed);
            return new GovPolicyFetchResult(List.of(policy("半导体政策", "https://policy.example/a", "2026-09-07")), List.of());
        });
        assertThat(service.fetchPolicies(started).evidence()).singleElement()
                .satisfies(row -> assertThat(row.observedAt()).isEqualTo(completed));

        when(acquisitionClock.instant()).thenReturn(started);
        when(announcements.fetchAnnouncements(any(CompanyProfile.class), eq(30))).thenAnswer(call -> {
            when(acquisitionClock.instant()).thenReturn(completed);
            return List.of(announcement("recent", "600001", "会议决议", "2026-09-07T00:00:00Z"));
        });
        assertThat(service.review("600001", "示例公司", "半导体", started).evidence()).singleElement()
                .satisfies(row -> assertThat(row.observedAt()).isEqualTo(completed));
    }

    @Test
    void industryPoliciesRequireSpecificTermsWithoutClaimingCompanyBenefit() {
        policyRows(List.of(
                policy("推动科技创新及扩大内需的政策", "https://policy.example/broad", "2026-09-07"),
                policy("促进集成电路产业发展意见", "https://policy.example/chip", "2026-09-07"),
                policy("半导体产业政策通知", "https://policy.example/unknown", null),
                policy("农业稳产保供意见", "https://policy.example/agri", "2026-09-07")));
        PolicyBundle bundle = service.fetchPolicies(AS_OF);

        List<Evidence> matched = service.relatedPolicies("半导体", bundle);

        assertThat(matched).extracting(Evidence::url)
                .containsExactly("https://policy.example/chip", "https://policy.example/unknown");
        assertThat(matched).allMatch(row -> row.interpretation().contains("不证明公司")
                && row.interpretation().contains("关键词"));
        assertThat(service.relatedPolicies("电力", bundle)).isEmpty();
        assertThat(service.relatedPolicies("", bundle)).isEmpty();
    }

    @Test
    void matchingRecentAnnouncementsAndAnOlderBoundaryAllowOnlyNoTitleRiskConclusion() {
        announcementRows(List.of(announcement("daily", "600001", "董事会会议决议", "2026-09-07T00:00:00Z"),
                announcement("old", "600001", "股东减持计划", "2026-09-01T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("NO_TITLE_RISK");
        assertThat(result.evidence()).extracting(Evidence::title).containsExactly("董事会会议决议");
        assertThat(result.evidence().get(0).url()).startsWith("https://static.cninfo.com.cn/");
        assertThat(result.evidence().get(0).observedAt()).isEqualTo(OBSERVED);
        assertThat(result.notes()).anyMatch(note -> note.contains("标题") && note.contains("不代表"));
        verify(announcements).fetchAnnouncements(argThat(company -> "600001".equals(company.symbol())
                && "示例公司".equals(company.name()) && "半导体".equals(company.industry())), eq(30));
    }

    @Test
    void riskTitlesRequireOriginalReadingAndRemainRiskWhenCoverageIsUnknown() {
        announcementRows(List.of(announcement("risk", "600001", "关于股东减持计划实施完毕的公告", "2026-09-07T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("TITLE_RISK");
        assertThat(result.evidence()).singleElement().satisfies(row -> {
            assertThat(row.interpretation()).contains("原文", "线索", "不代表已证实利空");
            assertThat(row.temporalStatus()).isEqualTo("RECENT");
        });
        assertThat(result.notes()).anyMatch(note -> note.contains("WINDOW_LIMITED") && note.contains("UNKNOWN"));
    }

    @Test
    void aValidRiskRemainsVisibleAlongsideUnusableDatedRows() {
        announcementRows(List.of(announcement("risk", "600001", "关于行政处罚的公告", "2026-09-07T00:00:00Z"),
                new CninfoAnnouncement("undated", "600001", "示例公司", "org", "会议决议", 0L, "finalpage/a.PDF"),
                announcement("old", "600001", "会议决议", "2026-09-01T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("TITLE_RISK");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.notes()).anyMatch(note -> note.contains("UNKNOWN") && note.contains("日期无法确认"));
    }

    @Test
    void wrongSecurityFutureAndEpochZeroAnnouncementsCannotBecomeEvidenceOrClearRisk() {
        announcementRows(List.of(
                announcement("wrong", "600002", "立案调查公告", "2026-09-07T00:00:00Z"),
                announcement("future", "600001", "停牌公告", "2026-09-08T03:00:00Z"),
                new CninfoAnnouncement("zero", "600001", "示例公司", "org", "处罚公告", 0L, "finalpage/zero.PDF"),
                announcement("old", "600001", "董事会会议决议", "2026-09-01T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.notes()).anyMatch(note -> note.contains("证券代码"));
        assertThat(result.notes()).anyMatch(note -> note.contains("未来"));
        assertThat(result.notes()).anyMatch(note -> note.contains("日期无法确认"));
    }

    @Test
    void olderAnnouncementForAnotherSecurityDoesNotProveCoverage() {
        announcementRows(List.of(announcement("current", "600001", "会议决议", "2026-09-07T00:00:00Z"),
                announcement("old-other", "600002", "会议决议", "2026-08-01T00:00:00Z")));

        assertThat(review().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void latestThirtyRecentRowsMayBeTruncatedEvenWhenAnIgnoredThirtyFirstRowIsOld() {
        List<CninfoAnnouncement> rows = new ArrayList<>(IntStream.range(0, 30)
                .mapToObj(i -> announcement("recent" + i, "600001", "会议决议" + i, "2026-09-07T00:00:00Z")).toList());
        rows.add(announcement("old", "600001", "会议决议", "2026-08-01T00:00:00Z"));
        announcementRows(rows);

        EventReview result = review();

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.evidence()).hasSize(30);
        assertThat(result.notes()).anyMatch(note -> note.contains("30") && note.contains("截断"));
    }

    @Test
    void aShortRecentPageIsNotProofOfSevenDayCoverage() {
        announcementRows(List.of(announcement("recent", "600001", "会议决议", "2026-09-07T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.notes()).anyMatch(note -> note.contains("WINDOW_LIMITED"));
    }

    @Test
    void emptyOrUnavailableAnnouncementSourceCannotDeclareNoTitleRisk() {
        announcementRows(List.of());
        assertThat(review().status()).isEqualTo("UNKNOWN");
        when(announcements.fetchAnnouncements(any(CompanyProfile.class), eq(30)))
                .thenThrow(new IllegalStateException("offline"));
        assertThat(review().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void announcementDuplicatesDoNotAmplifyOneRiskAndInvalidLinksRemainUnknown() {
        CninfoAnnouncement risk = announcement("risk", "600001", "关于诉讼事项公告", "2026-09-07T00:00:00Z");
        announcementRows(List.of(risk, risk,
                announcement("old", "600001", "会议决议", "2026-09-01T00:00:00Z")));
        assertThat(review().evidence()).hasSize(1);

        when(announcements.downloadUrl(any())).thenReturn("javascript:alert(1)");
        when(announcements.disclosureUrl(any(), any(), any(), anyLong())).thenReturn("https:///missing-host");
        EventReview invalid = review();
        assertThat(invalid.status()).isEqualTo("UNKNOWN");
        assertThat(invalid.evidence()).isEmpty();
    }

    @Test
    void conflictingDuplicateUrlCannotSuppressARiskTitleOrItsEvidence() {
        CninfoAnnouncement harmless = announcement("shared", "600001", "会议决议", "2026-09-07T00:00:00Z");
        CninfoAnnouncement risk = new CninfoAnnouncement("risk", "600001", "示例公司", "org",
                "关于诉讼事项公告", harmless.announcementTime(), harmless.adjunctUrl());
        announcementRows(List.of(harmless, risk,
                announcement("old", "600001", "会议决议", "2026-09-01T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("TITLE_RISK");
        assertThat(result.evidence()).anyMatch(row -> row.title().equals("关于诉讼事项公告")
                && row.interpretation().contains("风险线索"));
        assertThat(result.notes()).anyMatch(note -> note.contains("UNKNOWN") && note.contains("重复"));
    }

    @Test
    void conflictingDuplicateWithoutARiskTitleKeepsCoverageUnknown() {
        CninfoAnnouncement first = announcement("shared", "600001", "董事会会议决议", "2026-09-07T00:00:00Z");
        CninfoAnnouncement conflicting = new CninfoAnnouncement("other", "600001", "示例公司", "org",
                "股东会会议决议", first.announcementTime(), first.adjunctUrl());
        announcementRows(List.of(first, conflicting,
                announcement("old", "600001", "会议决议", "2026-09-01T00:00:00Z")));

        EventReview result = review();

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.notes()).anyMatch(note -> note.contains("重复"));
    }

    private EventReview review() { return service.review("600001", "示例公司", "半导体", AS_OF); }

    private void policyRows(List<GovPolicyItem> rows) {
        when(policies.fetchLatestPoliciesWithStatus(40)).thenReturn(new GovPolicyFetchResult(rows, List.of()));
    }

    private void announcementRows(List<CninfoAnnouncement> rows) {
        when(announcements.fetchAnnouncements(any(CompanyProfile.class), eq(30))).thenReturn(rows);
    }

    private GovPolicyItem policy(String title, String url, String date) {
        return new GovPolicyItem("配置政策源", "html", title, url, date, 60);
    }

    private CninfoAnnouncement announcement(String id, String symbol, String title, String date) {
        return new CninfoAnnouncement(id, symbol, "示例公司", "org", title,
                Instant.parse(date).toEpochMilli(), "finalpage/" + id + ".PDF");
    }
}
