package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyConceptClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void proxyEnvironmentUsesTheSameHttpsFirstPrecedenceAsApplicationClient() {
        var address = EastMoneyConceptClient.proxyFromEnvironment(Map.of(
                "https_proxy", "http://127.0.0.1:19001", "HTTPS_PROXY", "http://127.0.0.1:19002",
                "http_proxy", "http://127.0.0.1:19003", "HTTP_PROXY", "http://127.0.0.1:19004")).orElseThrow();
        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(19001);
    }

    @Test
    void blankProxyValuesAreSkippedAndUppercaseHttpFallbackIsSupported() {
        var address = EastMoneyConceptClient.proxyFromEnvironment(Map.of(
                "https_proxy", " ", "HTTPS_PROXY", "", "HTTP_PROXY", "http://127.0.0.1:19004")).orElseThrow();
        assertThat(address.getPort()).isEqualTo(19004);
        assertThat(EastMoneyConceptClient.proxyFromEnvironment(Map.of())).isEmpty();
    }

    @Test
    void malformedProxyIsIgnoredWithoutRetryingOtherConfigurationEntries() {
        for (String value : List.of("invalid", "http://127.0.0.1", "http://127.0.0.1:99999", "http://[")) {
            assertThat(EastMoneyConceptClient.proxyFromEnvironment(Map.of(
                    "https_proxy", value, "HTTP_PROXY", "http://127.0.0.1:19004"))).isEmpty();
        }
    }

    @Test
    void boardsUseConceptFilterStableCodePaginationAndOriginalSourceTime() {
        FixtureClient client = new FixtureClient(page(101, boardRows(1, 100)), page(101, boardRows(101, 1)));

        EastMoneyConceptClient.BoardSnapshot snapshot = client.fetchBoards();

        assertThat(snapshot.boards()).hasSize(101);
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(snapshot.boards().get(0).marketTimestamp()).isEqualTo(Instant.ofEpochSecond(1793000000));
        assertThat(snapshot.boards().get(0).changePercent()).isEqualByComparingTo("2.35");
        assertThat(snapshot.boards().get(0).amount()).isEqualByComparingTo("123456.78");
        assertThat(snapshot.boards().get(0).advancing()).isEqualTo(4);
        assertThat(snapshot.boards().get(0).declining()).isEqualTo(2);
        assertThat(snapshot.boards().get(0).unchanged()).isZero();
        assertThat(client.urls).hasSize(2);
        assertThat(decoded(client.urls.get(0))).contains("pn=1", "pz=100", "fid=f12", "fs=m:90 t:3 f:!50",
                "fltt=2", "fields=f12,f14,f3,f6,f104,f105,f106,f124");
        assertThat(decoded(client.urls.get(1))).contains("pn=2");
    }

    @Test
    void objectFormatAndZeroMetricsAreRetainedWhileMissingMetricsAndTimeStayNull() {
        ObjectNode first = MAPPER.createObjectNode().put("f12", "BK1090").put("f14", "机器人概念")
                .put("f3", 0).put("f6", "-").put("f104", 0).put("f105", "-").put("f106", 0);
        ObjectNode second = MAPPER.createObjectNode().put("f12", "BK1184").put("f14", "人形机器人")
                .put("f3", "-").put("f124", "-");
        ObjectNode diff = MAPPER.createObjectNode().set("0", first);
        diff.set("1", second);
        FixtureClient client = new FixtureClient(MAPPER.createObjectNode().put("rc", 0).set("data",
                MAPPER.createObjectNode().put("total", 2).set("diff", diff)).toString());

        List<EastMoneyConceptClient.Board> boards = client.fetchBoards().boards();

        assertThat(boards.get(0).changePercent()).isEqualByComparingTo("0");
        assertThat(boards.get(0).amount()).isNull();
        assertThat(boards.get(0).advancing()).isZero();
        assertThat(boards.get(0).declining()).isNull();
        assertThat(boards.get(0).marketTimestamp()).isNull();
        assertThat(boards.get(1).changePercent()).isNull();
        assertThat(boards.get(1).marketTimestamp()).isNull();
    }

    @Test
    void membersUseOnlyTheSelectedBoardAndRetainFields() {
        ArrayNode rows = MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                .put("f12", "600100").put("f14", "同方股份").put("f2", "6.80").put("f3", "0")
                .put("f6", "1234567.89").put("f8", "1.25").put("f124", 1793000000));
        FixtureClient client = new FixtureClient(page(1, rows));

        EastMoneyConceptClient.MemberSnapshot snapshot = client.fetchMembers("BK1090");

        assertThat(snapshot.total()).isOne();
        assertThat(snapshot.members()).hasSize(1);
        assertThat(snapshot.members().get(0).symbol()).isEqualTo("600100");
        assertThat(snapshot.members().get(0).latestPrice()).isEqualByComparingTo("6.80");
        assertThat(snapshot.members().get(0).changePercent()).isEqualByComparingTo("0");
        assertThat(snapshot.members().get(0).amount()).isEqualByComparingTo("1234567.89");
        assertThat(snapshot.members().get(0).turnoverRate()).isEqualByComparingTo("1.25");
        assertThat(snapshot.members().get(0).marketTimestamp()).isEqualTo(Instant.ofEpochSecond(1793000000));
        assertThat(decoded(client.urls.get(0))).contains("fs=b:BK1090 f:!50", "fid=f12",
                "fields=f12,f14,f2,f3,f6,f8,f124");
    }

    @Test
    void invalidBoardCodeNeverRequestsTheNetwork() {
        FixtureClient client = new FixtureClient();
        for (String code : new String[]{null, "", "BKbad", "600100", "BK1090&pn=2"}) {
            assertThatThrownBy(() -> client.fetchMembers(code)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(client.urls).isEmpty();
    }

    @Test
    void onlyExplicitCompleteEmptyResponsesAreAccepted() {
        assertThat(new FixtureClient(page(0, MAPPER.createArrayNode())).fetchBoards().boards()).isEmpty();
        for (String body : List.of("{}", "{\"rc\":0,\"data\":null}",
                "{\"rc\":0,\"data\":{\"diff\":[]}}", "{\"rc\":1,\"data\":{\"total\":0,\"diff\":[]}}",
                "{\"rc\":4294967296,\"data\":{\"total\":0,\"diff\":[]}}",
                "{\"rc\":0,\"data\":{\"total\":0,\"diff\":null}}", page(1, MAPPER.createArrayNode()))) {
            assertThatThrownBy(() -> new FixtureClient(body).fetchBoards()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void changingTotalAndIncompleteLaterPageCannotReturnPartialData() {
        for (String secondPage : List.of(page(102, boardRows(101, 1)), page(101, MAPPER.createArrayNode()))) {
            FixtureClient client = new FixtureClient(page(101, boardRows(1, 100)), secondPage);
            assertThatThrownBy(client::fetchBoards).isInstanceOf(IllegalStateException.class);
            assertThat(client.urls).hasSize(2);
        }
    }

    @Test
    void duplicateOrMalformedIdentityCannotBecomeACompleteSnapshot() {
        for (ArrayNode rows : List.of(
                MAPPER.createArrayNode().add(boardRows(1, 1).get(0)).add(boardRows(1, 1).get(0)),
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("f12", "BAD").put("f14", "错误")),
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("f12", "BK1090").put("f14", " ")))) {
            assertThatThrownBy(() -> new FixtureClient(page(rows.size(), rows)).fetchBoards())
                    .isInstanceOf(IllegalStateException.class);
        }
        FixtureClient duplicateAcrossPages = new FixtureClient(page(101, boardRows(1, 100)), page(101, boardRows(1, 1)));
        assertThatThrownBy(duplicateAcrossPages::fetchBoards).isInstanceOf(IllegalStateException.class);
        ArrayNode invalidMember = MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("f12", "BK1090").put("f14", "板块"));
        assertThatThrownBy(() -> new FixtureClient(page(1, invalidMember)).fetchMembers("BK1090"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oversizedDirectoryFailsAfterOneRequestWithoutTruncation() {
        FixtureClient client = new FixtureClient(page(1001, boardRows(1, 100)));
        assertThatThrownBy(client::fetchBoards).isInstanceOf(IllegalStateException.class).hasMessageContaining("1000");
        assertThat(client.urls).hasSize(1);
    }

    @Test
    void aTransportFailureIsNotRetriedOrFollowedByLaterPages() {
        FixtureClient client = new FixtureClient(page(101, boardRows(1, 100)));
        client.failureAt = 1;
        assertThatThrownBy(client::fetchBoards).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
        assertThat(client.urls).hasSize(2);
    }

    @Test
    void requestTimeoutIsCappedAtFiveSecondsAndRemainingTotalBudget() {
        FixtureClient client = new FixtureClient(page(101, boardRows(1, 100)), page(101, boardRows(101, 1)));
        client.advanceAfterFetch = Duration.ofSeconds(16).toNanos();
        assertThatThrownBy(client::fetchBoards).isInstanceOf(IllegalStateException.class).hasMessageContaining("20秒");
        assertThat(client.timeouts).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(4));
    }

    @Test
    void exhaustedOverallBudgetStopsBeforeAnotherPageAndRejectsLateFinalResponses() {
        for (int total : List.of(1, 101)) {
            FixtureClient client = new FixtureClient(page(total, boardRows(1, Math.min(100, total))));
            client.advanceAfterFetch = Duration.ofSeconds(20).toNanos();
            assertThatThrownBy(client::fetchBoards).isInstanceOf(IllegalStateException.class).hasMessageContaining("20秒");
            assertThat(client.urls).hasSize(1);
        }
    }

    @Test
    void interruptedSendPreservesCancellationAndThreadInterruptFlag() {
        FixtureClient client = new FixtureClient();
        client.interrupted = true;
        try {
            assertThatThrownBy(client::fetchBoards).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(client.urls).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    private static ArrayNode boardRows(int start, int count) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int index = start; index < start + count; index++) {
            rows.add(MAPPER.createObjectNode().put("f12", "BK" + (1000 + index)).put("f14", "题材" + index)
                    .put("f3", "2.35").put("f6", "123456.78").put("f104", 4).put("f105", 2)
                    .put("f106", 0).put("f124", 1793000000));
        }
        return rows;
    }

    private static String page(int total, ArrayNode rows) {
        return MAPPER.createObjectNode().put("rc", 0).set("data",
                MAPPER.createObjectNode().put("total", total).set("diff", rows)).toString();
    }

    private static String decoded(URI uri) {
        return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
    }

    private static final class FixtureClient extends EastMoneyConceptClient {
        private final List<String> responses;
        private final List<URI> urls = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private long elapsedNanos;
        private long advanceAfterFetch;
        private int failureAt = -1;
        private boolean interrupted;

        FixtureClient(String... responses) {
            super(MAPPER);
            this.responses = List.of(responses);
        }

        @Override
        String fetchBody(URI uri, Duration timeout) throws IOException, InterruptedException {
            int index = urls.size();
            urls.add(uri);
            timeouts.add(timeout);
            if (interrupted) throw new InterruptedException("fixture cancellation");
            if (index == failureAt) throw new IOException("fixture source unavailable");
            elapsedNanos += advanceAfterFetch;
            return responses.get(index);
        }

        @Override
        long nanoTime() {
            return elapsedNanos;
        }
    }
}
