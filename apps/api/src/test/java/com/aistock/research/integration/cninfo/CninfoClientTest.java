package com.aistock.research.integration.cninfo;

import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.fiveday.FiveDayContextModels;
import com.aistock.research.fiveday.FiveDayEventEvidenceService;
import com.aistock.research.integration.gov.GovPolicyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CninfoClientTest {
    private static final Instant AS_OF = Instant.parse("2026-09-08T02:00:00Z");
    private MockRestServiceServer server;
    private FiveDayEventEvidenceService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        LiveDataProperties properties = new LiveDataProperties(null, null, 200, null, null,
                "https://cninfo.example/announcements", null, false, null, null, null, null, null);
        CninfoClient client = new CninfoClient(builder.build(), new ObjectMapper(), properties);
        service = new FiveDayEventEvidenceService(mock(GovPolicyClient.class), client,
                Clock.fixed(AS_OF.plusSeconds(2), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"announcementId", "announcementTitle", "secCode"})
    void missingRawFieldsCannotBeDiscardedOrFilledToDeclareNoTitleRisk(String missingField) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var malformed = mapper.readTree(announcement("unread", "会议决议", "2026-09-07T00:00:00Z"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed).remove(missingField);
        expectAnnouncements("{\"announcements\":[" + malformed + ","
                + announcement("old", "会议决议", "2026-09-01T00:00:00Z") + "]}");

        FiveDayContextModels.EventReview result = service.review("600001", "示例公司", "半导体", AS_OF);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.notes()).anyMatch(note -> note.contains("公告源暂不可用"));
        server.verify();
    }

    @Test
    void malformedRawItemsCannotDisappearFromCoverage() {
        expectAnnouncements("{\"announcements\":[null,"
                + announcement("old", "会议决议", "2026-09-01T00:00:00Z") + "]}");

        FiveDayContextModels.EventReview result = service.review("600001", "示例公司", "半导体", AS_OF);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.notes()).anyMatch(note -> note.contains("公告源暂不可用"));
        server.verify();
    }

    @Test
    void aValidRawPageStillAllowsACompleteTitleReviewWithoutSecurityName() {
        expectAnnouncements("{\"announcements\":["
                + announcement("recent", "会议决议", "2026-09-07T00:00:00Z") + ","
                + announcement("old", "会议决议", "2026-09-01T00:00:00Z") + "]}");

        FiveDayContextModels.EventReview result = service.review("600001", "示例公司", "半导体", AS_OF);

        assertThat(result.status()).isEqualTo("NO_TITLE_RISK");
        assertThat(result.evidence()).singleElement()
                .satisfies(row -> assertThat(row.title()).isEqualTo("会议决议"));
        server.verify();
    }

    private void expectAnnouncements(String json) {
        server.expect(requestTo("https://www.cninfo.com.cn/new/data/szse_stock.json"))
                .andRespond(withSuccess("{\"stockList\":[{\"code\":\"600001\",\"orgId\":\"org\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://cninfo.example/announcements"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String announcement(String id, String title, String time) {
        return """
                {"announcementId":"%s","secCode":"600001","orgId":"org","announcementTitle":"%s", \
                "announcementTime":%d,"adjunctUrl":"finalpage/%s.PDF"}
                """.formatted(id, title, Instant.parse(time).toEpochMilli(), id);
    }
}
