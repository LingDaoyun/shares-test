package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GovPolicyClientTest {

    private RuntimeConfigStore store;
    private MockRestServiceServer server;
    private GovPolicyClient client;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GovPolicyClient(
                builder.build(),
                new ObjectMapper(),
                store
        );
    }

    @Test
    void nextFetchUsesTheLatestDatabasePolicySources() {
        when(store.readPolicySources())
                .thenReturn(List.of(new PolicySourceConfig(
                        "来源甲", "html", "https://policy-a.example/list", 90)))
                .thenReturn(List.of(new PolicySourceConfig(
                        "来源乙", "html", "https://policy-b.example/list", 80)));
        server.expect(requestTo("https://policy-a.example/list"))
                .andRespond(withSuccess(
                        "<a href='/a'>政策方案甲正式发布通知</a>",
                        new MediaType("text", "html", UTF_8)));
        server.expect(requestTo("https://policy-b.example/list"))
                .andRespond(withSuccess(
                        "<a href='/b'>政策方案乙正式发布通知</a>",
                        new MediaType("text", "html", UTF_8)));

        GovPolicyFetchResult first = client.fetchLatestPoliciesWithStatus(8);
        GovPolicyFetchResult second = client.fetchLatestPoliciesWithStatus(8);

        assertThat(first.items()).extracting(GovPolicyItem::source).containsOnly("来源甲");
        assertThat(second.items()).extracting(GovPolicyItem::source).containsOnly("来源乙");
        server.verify();
    }

    @Test
    void explicitlyEmptyDatabaseListDisablesPolicyFetching() {
        when(store.readPolicySources()).thenReturn(List.of());

        GovPolicyFetchResult result = client.fetchLatestPoliciesWithStatus(8);

        assertThat(result.items()).isEmpty();
        assertThat(result.failedSources()).isEmpty();
        server.verify();
    }
}
