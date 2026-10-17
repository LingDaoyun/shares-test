package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static com.aistock.research.fiveday.FiveDayModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayExecutionCheckServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T02:00:00Z");
    private static final Instant FROZEN = Instant.parse("2026-09-07T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-09-08");
    private static final LocalDate SIGNAL = LocalDate.parse("2026-09-07");
    private final EastMoneyClient market = mock(EastMoneyClient.class);
    private final FiveDayEventEvidenceService evidence = mock(FiveDayEventEvidenceService.class);
    private final TradingClockService clock = clock(NOW);
    private final FiveDayExecutionCheckService service = service(clock);

    @BeforeEach void inputs() {
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(quotes(200, 200, "1"));
        when(evidence.review(eq("600001"), anyString(), anyString(), any())).thenReturn(events("NO_TITLE_RISK"));
    }

    @Test void currentEligibleEntryPassesWithoutChangingFrozenEvidence() {
        Candidate candidate = candidate(TODAY, "9.5", "10.2");
        Snapshot frozen = snapshot(candidate, "five-day-context-v2", "READY", true, "ELIGIBLE", FROZEN);
        var result = service.check(frozen, candidate);
        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.priceState()).isEqualTo("IN_RANGE");
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.events().status()).isEqualTo("NO_TITLE_RISK");
        assertThat(result.notes()).anyMatch(note -> note.contains("NORMAL") && note.contains("200"));
        assertThat(frozen.context().assessedAt()).isEqualTo(FROZEN);
        verify(market).fetchAshareQuoteSnapshot(10000);
        verify(evidence).review("600001", "示例公司", "示例行业", NOW);
    }

    @Test void v1NonForwardAndLateFrozenSignalsShortCircuitProviders() {
        Candidate candidate = candidate(TODAY, "9.5", "10.2");
        assertThat(service.check(snapshot(candidate, VERSION, "READY", true, "ELIGIBLE", FROZEN), candidate).status()).isEqualTo("BLOCKED");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "READY", false, "ELIGIBLE", FROZEN), candidate).status()).isEqualTo("BLOCKED");
        Instant late = Instant.parse("2026-09-08T01:15:01Z");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "READY", true, "ELIGIBLE", late), candidate).status()).isEqualTo("BLOCKED");
        verifyNoInteractions(market, evidence);
    }

    @Test void unfinishedOrUnqualifiedFrozenCandidateCannotCheckExecution() {
        Candidate candidate = candidate(TODAY, "9.5", "10.2");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "SCANNING", true, "ELIGIBLE", FROZEN), candidate).status()).isEqualTo("WATCH");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "READY", true, "WATCH", FROZEN), candidate).status()).isEqualTo("WATCH");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "READY", true, "AVOID", FROZEN), candidate).status()).isEqualTo("BLOCKED");
        assertThat(service.check(snapshot(candidate, "five-day-context-v2", "READY", true, "UNKNOWN", FROZEN), candidate).status()).isEqualTo("UNKNOWN");
        verifyNoInteractions(market, evidence);
    }

    @Test void pastFutureAndUnverifiedEntryDatesShortCircuitProviders() {
        for (LocalDate entry : List.of(TODAY.minusDays(1), TODAY.plusDays(1))) {
            Candidate candidate = candidate(entry, "9.5", "10.2");
            assertThat(service.check(snapshot(candidate), candidate).status()).isEqualTo("BLOCKED");
        }
        Candidate future = candidate(LocalDate.parse("2027-09-08"), "9.5", "10.2");
        assertThat(service(clock(Instant.parse("2027-09-08T02:00:00Z"))).check(snapshot(future), future).status()).isEqualTo("UNKNOWN");
        verifyNoInteractions(market, evidence);
    }

    @Test void lunchAuctionAndAfterCloseCannotPassAnEntryCheck() {
        Candidate candidate = candidate(TODAY, "9.5", "10.2");
        for (String at : List.of("2026-09-08T01:20:00Z", "2026-09-08T03:30:01Z", "2026-09-08T04:00:00Z", "2026-09-08T06:57:00Z", "2026-09-08T07:00:00Z", "2026-09-08T07:00:01Z")) {
            assertThat(service(clock(Instant.parse(at))).check(snapshot(candidate), candidate).status()).isEqualTo("WATCH");
        }
        verifyNoInteractions(market, evidence);
    }

    @Test void quoteMustHaveItsOwnCurrentDateAndMarketTime() {
        for (EastMoneyQuote invalid : List.of(
                quote("600001", "10.1", "10", "1", "100", TODAY, null),
                quote("600001", "10.1", "10", "1", "100", TODAY.minusDays(1), NOW),
                quote("600001", "10.1", "10", "1", "100", TODAY, NOW.plusSeconds(1)),
                quote("600001", "10.1", "10", "1", "100", TODAY, NOW.minusSeconds(301)))) {
            replaceCandidateQuote(invalid);
            assertThat(check().status()).isEqualTo("UNKNOWN");
            assertThat(check().priceState()).isEqualTo("UNKNOWN");
        }
    }

    @Test void exactlyFiveMinuteOldQuoteRemainsValid() {
        replaceCandidateQuote(quote("600001", "10.1", "10", "1", "100", TODAY, NOW.minusSeconds(300)));
        assertThat(check().status()).isEqualTo("PASSED");
    }

    @Test void duplicateCandidateOrMissingIdentityCannotBeChosenArbitrarily() {
        List<EastMoneyQuote> duplicates = new ArrayList<>(quotes(200, 200, "1").quotes());
        duplicates.add(duplicates.get(0));
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(duplicates, 200));
        assertThat(check().status()).isEqualTo("UNKNOWN");
        duplicates.removeIf(quote -> quote.symbol().equals("600001"));
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(duplicates, 200));
        assertThat(check().status()).isEqualTo("UNKNOWN");
    }

    @Test void staleMarketCoverageAndUnknownExpectedCountCannotPass() {
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(quotes(199, 200, "1"));
        assertThat(check().status()).isEqualTo("UNKNOWN");
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(quotes(200, 251, "1"));
        assertThat(check().status()).isEqualTo("UNKNOWN");
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(quotes(200, 0, "1"));
        assertThat(check().status()).isEqualTo("UNKNOWN");
        List<EastMoneyQuote> stale = new ArrayList<>(quotes(200, 200, "1").quotes());
        stale.set(1, quote("600002", "10.1", "10", "1", "100", TODAY, NOW.minusSeconds(301)));
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(stale, 200));
        assertThat(check().status()).isEqualTo("UNKNOWN");
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(quotes(200, 250, "1"));
        assertThat(check().status()).isEqualTo("PASSED");
    }

    @Test void weakMarketBlocksAndCautionRemainsWatchUsingTheSharedClassifier() {
        List<EastMoneyQuote> weak = new ArrayList<>(quotes(200, 200, "-1").quotes());
        weak.set(0, quote("600001", "10.1", "10", "1", "100", TODAY, NOW));
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(weak, 200));
        assertThat(check().status()).isEqualTo("BLOCKED");
        List<EastMoneyQuote> caution = new ArrayList<>(quotes(200, 200, "0").quotes());
        caution.set(0, weak.get(0));
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(caution, 200));
        assertThat(check().status()).isEqualTo("WATCH");
    }

    @Test void missingPriceOrVolumeIsUnknownAndNoTradingVolumeBlocks() {
        for (EastMoneyQuote invalid : List.of(
                quote("600001", null, "10", "1", "100", TODAY, NOW),
                quote("600001", "10.1", null, "1", "100", TODAY, NOW),
                quote("600001", "10.1", "10", null, "100", TODAY, NOW),
                quote("600001", "10.1", "10", "1", null, TODAY, NOW))) {
            replaceCandidateQuote(invalid);
            assertThat(check().status()).isEqualTo("UNKNOWN");
        }
        replaceCandidateQuote(quote("600001", "10.1", "10", "1", "0", TODAY, NOW));
        assertThat(check().status()).isEqualTo("BLOCKED");
    }

    @Test void bothOpenAndLatestMustStayWithinTheFrozenBoundaries() {
        for (EastMoneyQuote outside : List.of(
                quote("600001", "10.1", "10.3", "1", "100", TODAY, NOW),
                quote("600001", "10.1", "9.4", "1", "100", TODAY, NOW),
                quote("600001", "10.3", "10", "3", "100", TODAY, NOW),
                quote("600001", "9.4", "10", "-6", "100", TODAY, NOW),
                quote("600001", "9.5", "10", "-5", "100", TODAY, NOW),
                quote("600001", "10.1", "9.5", "1", "100", TODAY, NOW))) {
            replaceCandidateQuote(outside);
            assertThat(check().status()).isEqualTo("BLOCKED");
            assertThat(check().priceState()).isEqualTo("OUT_OF_RANGE");
        }
        replaceCandidateQuote(quote("600001", "10.2", "9.51", "2", "100", TODAY, NOW));
        assertThat(check().status()).isEqualTo("PASSED");
    }

    @Test void aChangedAdjustmentBasisIsUnknownBeforeApplyingOldEntryBounds() {
        replaceCandidateQuote(quote("600001", "5.05", "5", "1", "100", TODAY, NOW));
        var result = check();
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.priceState()).isEqualTo("UNKNOWN");
        assertThat(result.notes()).anyMatch(note -> note.contains("基准") && note.contains("0.5%"));
    }

    @Test void exactHalfPercentPriceBasisTolerancePassesButLargerDoesNot() {
        replaceCandidateQuote(quote("600001", "10.1505", "10", "1", "100", TODAY, NOW));
        assertThat(check().status()).isEqualTo("PASSED");
        replaceCandidateQuote(quote("600001", "10.15151", "10", "1", "100", TODAY, NOW));
        assertThat(check().status()).isEqualTo("UNKNOWN");
    }

    @Test void currentOrOpeningLimitUpLikeQuotesBlockEvenInsideWideBounds() {
        Candidate candidate = candidate(TODAY, "9", "12");
        for (EastMoneyQuote limited : List.of(
                quote("600001", "10.95", "10", "9.5", "100", TODAY, NOW),
                quote("600001", "10.1", "10.95", "1", "100", TODAY, NOW))) {
            replaceCandidateQuote(limited);
            var result = service.check(snapshot(candidate), candidate);
            assertThat(result.status()).isEqualTo("BLOCKED");
            assertThat(result.priceState()).isEqualTo("LIMIT_UP_LIKE");
        }
    }

    @Test void freshAnnouncementRiskOverridesTheFrozenNoRiskAssessment() {
        when(evidence.review(anyString(), anyString(), anyString(), any())).thenReturn(events("TITLE_RISK"));
        assertThat(check().status()).isEqualTo("BLOCKED");
        assertThat(check().events().status()).isEqualTo("TITLE_RISK");
        when(evidence.review(anyString(), anyString(), anyString(), any())).thenReturn(events("UNKNOWN"));
        assertThat(check().status()).isEqualTo("UNKNOWN");
    }

    @Test void providerFailuresAreUnknownWithoutLeakingExceptionDetails() {
        when(market.fetchAshareQuoteSnapshot(10000)).thenThrow(new IllegalStateException("private provider detail"));
        var result = check();
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.notes()).noneMatch(note -> note.contains("private"));
        doReturn(quotes(200, 200, "1")).when(market).fetchAshareQuoteSnapshot(10000);
        when(evidence.review(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("private provider detail"));
        assertThat(check().status()).isEqualTo("UNKNOWN");
    }

    private FiveDayExecutionCheckService.ExecutionCheck check() {
        Candidate candidate = candidate(TODAY, "9.5", "10.2");
        return service.check(snapshot(candidate), candidate);
    }
    private Snapshot snapshot(Candidate candidate) { return snapshot(candidate, "five-day-context-v2", "READY", true, "ELIGIBLE", FROZEN); }
    private Snapshot snapshot(Candidate candidate, String version, String status, boolean forward, String decision, Instant captured) {
        CandidateAssessment assessment = new CandidateAssessment(candidate.symbol(), candidate.industry(), decision,
                new Turning(true, d("1"), d("80"), List.of()), events("NO_TITLE_RISK"), List.of(), List.of());
        Context context = new Context(captured, null, List.of(), List.of(assessment), List.of(), "READY", List.of());
        return new Snapshot(status, version, SIGNAL, captured, forward, false, false, "fixture", Rules.defaults(),
                new Coverage(200, 1, 1, 1, 1, 0, 0), List.of(candidate), List.of(), context);
    }
    private Candidate candidate(LocalDate entry, String stop, String maximum) {
        return new Candidate("600001", "示例公司", "示例行业", "MAIN", d("10"), d("10.2"), d("9.7"), d("5"), d("1"),
                d("1"), d("3"), d("2"), d("1.5"), d("100000000"), d("1000000"), d("1"), 3,
                true, true, "https://example.com/flow", d(stop), d(maximum), entry, entry.plusDays(6), List.of(), List.of(), List.of());
    }
    private void replaceCandidateQuote(EastMoneyQuote quote) {
        List<EastMoneyQuote> quotes = new ArrayList<>(quotes(201, 201, "1").quotes());
        quotes.set(0, quote);
        when(market.fetchAshareQuoteSnapshot(10000)).thenReturn(snapshotQuotes(quotes, 201));
    }
    private AshareQuoteSnapshot quotes(int count, int expected, String change) {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            quotes.add(quote(Integer.toString(600001 + i), "10.1", "10", change, "100", TODAY, NOW));
        }
        return snapshotQuotes(quotes, expected);
    }
    private AshareQuoteSnapshot snapshotQuotes(List<EastMoneyQuote> quotes, int expected) {
        return new AshareQuoteSnapshot(quotes, 10000, expected, quotes.size(), Math.max(0, expected - quotes.size()),
                quotes.size() == expected, "fixture", NOW);
    }
    private EastMoneyQuote quote(String symbol, String price, String open, String change, String volume, LocalDate date, Instant timestamp) {
        return new EastMoneyQuote(symbol, "示例公司", "SH", "示例行业", d(price), d(change), null, d(volume), null, null, null, null,
                "fixture", "https://example.com/quote", NOW, date, timestamp, null, d(open), null, null);
    }
    private FiveDayExecutionCheckService service(TradingClockService clock) { return new FiveDayExecutionCheckService(clock, market, new FiveDayContextEngine(clock), evidence); }
    private static TradingClockService clock(Instant now) { return new TradingClockService(Clock.fixed(now, TradingClockService.CHINA_MARKET_ZONE)); }
    private static EventReview events(String state) { return new EventReview(state, List.of(), List.of()); }
    private static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
}
