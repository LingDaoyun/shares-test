package com.aistock.research.policy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PolicySourceInfoTest {
    @Test
    void distinguishesMinistryOriginalFromLocalRepostWithoutPromotingItsAttribution() {
        PolicySourceInfo ministry = PolicySourceInfo.from("https://www.miit.gov.cn/zwgk/article.html", "电子信息司");
        PolicySourceInfo local = PolicySourceInfo.from("https://jxt.hubei.gov.cn/article.html", "工信微报");
        assertThat(ministry.publisher()).isEqualTo("工业和信息化部");
        assertThat(ministry.authorityTier()).isEqualTo("CENTRAL_DEPARTMENT");
        assertThat(local.authorityTier()).isEqualTo("LOCAL_GOVERNMENT");
        assertThat(local.attributedSource()).isEqualTo("工信微报");
        assertThat(ministry.authorityRank()).isGreaterThan(local.authorityRank());
    }

    @Test
    void claimedNamesAndLookalikeDomainsCannotBecomeOfficialSources() {
        for (String url : new String[]{"https://www.miit.gov.cn.example.com/a", "https://miit.gov.cn@news.example/a",
                "https://fakegov.cn/a", "javascript:alert(1)", "not a URL"}) {
            assertThat(PolicySourceInfo.from(url, "工业和信息化部").authorityTier()).isEqualTo("OTHER");
        }
        assertThat(PolicySourceInfo.from("https://www.gov.cn/a", null).authorityTier()).isEqualTo("CENTRAL_GOVERNMENT");
        assertThat(PolicySourceInfo.from("https://unknown.gov.cn/a", null).authorityTier()).isEqualTo("GOVERNMENT");
    }
}
