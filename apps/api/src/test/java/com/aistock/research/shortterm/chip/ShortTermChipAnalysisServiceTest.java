package com.aistock.research.shortterm.chip;

import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.tushare.TushareChipClient;
import com.aistock.research.integration.tushare.TushareChipFetchResult;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermChipAnalysisServiceTest {

    @Test
    void degradesToSingleSourceWhenTushareIsUnavailable() {
        TushareChipClient client = mock(TushareChipClient.class);
        ShortTermChipVerificationStore store = mock(ShortTermChipVerificationStore.class);
        TradingClockService clock = completedClock();
        when(store.find(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(client.fetchPerformance(anyString(), any()))
                .thenReturn(TushareChipFetchResult.failure("HTTP 429", 429));
        ShortTermChipAnalysisService service = service(client, store, clock);

        ShortTermChipSnapshot snapshot = service.analyze(quote(), bars(), true, Instant.parse("2026-07-30T06:50:00Z"));

        assertThat(snapshot.verificationStatus()).isEqualTo(ChipVerificationStatus.SINGLE_SOURCE);
        assertThat(snapshot.verificationCoefficient()).isEqualByComparingTo("1.00");
        assertThat(snapshot.contributionScore()).isPositive();
        assertThat(snapshot.dataGaps()).contains("外部筹码认证不可用", "HTTP 429");
        verify(store).save(anyString(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void reusesCachedExternalEvidenceDuringTailInsteadOfCallingTushare() {
        TushareChipClient client = mock(TushareChipClient.class);
        ShortTermChipVerificationStore store = mock(ShortTermChipVerificationStore.class);
        TradingClockService clock = completedClock();
        ExternalChipPerformance external = external();
        ShortTermChipSnapshot cachedSnapshot = verifiedSnapshot(external);
        when(store.find("002580", LocalDate.of(2026, 7, 30), ShortTermChipSnapshot.MODEL_VERSION))
                .thenReturn(Optional.of(new ShortTermChipVerificationEvidence(
                        "002580", LocalDate.of(2026, 7, 30), ShortTermChipSnapshot.MODEL_VERSION,
                        cachedSnapshot, external, Instant.now(), Instant.now(), null)));
        ShortTermChipAnalysisService service = service(client, store, clock);

        ShortTermChipSnapshot snapshot = service.analyze(quote(), bars(), false, Instant.now());

        assertThat(snapshot.verificationStatus()).isEqualTo(ChipVerificationStatus.VERIFIED);
        verify(client, never()).fetchPerformance(anyString(), any());
    }

    @Test
    void convertsPersistenceFailureIntoCandidateLevelGap() {
        TushareChipClient client = mock(TushareChipClient.class);
        ShortTermChipVerificationStore store = mock(ShortTermChipVerificationStore.class);
        TradingClockService clock = completedClock();
        when(store.find(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(client.fetchPerformance(anyString(), any()))
                .thenReturn(TushareChipFetchResult.failure("HTTP 429", 429));
        when(store.save(anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        ShortTermChipAnalysisService service = service(client, store, clock);

        ShortTermChipSnapshot snapshot = service.analyze(quote(), bars(), true, Instant.now());

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.dataGaps()).anyMatch(gap -> gap.contains("筹码认证缓存写入失败"));
    }

    private ShortTermChipAnalysisService service(
            TushareChipClient client,
            ShortTermChipVerificationStore store,
            TradingClockService clock
    ) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.chip.enabled", "true")
                .withProperty("research.short-term.chip.tushare.enabled", "true")
                .withProperty("research.short-term.chip.tushare.token", "configured-token");
        return new ShortTermChipAnalysisService(new ShortTermChipSettings(environment), client, store, clock);
    }

    private TradingClockService completedClock() {
        TradingClockService clock = mock(TradingClockService.class);
        when(clock.isCompletedDailyBar(any())).thenReturn(true);
        return clock;
    }

    private List<EastMoneyKLine> bars() {
        return IntStream.range(0, 120)
                .mapToObj(index -> new EastMoneyKLine(
                        "002580",
                        LocalDate.of(2026, 7, 30).minusDays(119L - index),
                        bd("9.80"), bd("10.00"), bd("10.20"), bd("9.60"),
                        bd("100000"), bd("100000000"), bd("2.00")
                ))
                .toList();
    }

    private EastMoneyQuote quote() {
        return new EastMoneyQuote(
                "002580", "圣阳股份", "深交所", "电池",
                bd("10.00"), bd("1.20"), bd("3.20"), bd("100000"), bd("100000000"),
                bd("20"), bd("2"), bd("20"),
                "东方财富", "https://quote.eastmoney.com/sz002580.html", Instant.now(),
                LocalDate.of(2026, 7, 30), Instant.now()
        );
    }

    private ExternalChipPerformance external() {
        return new ExternalChipPerformance(
                "002580", LocalDate.of(2026, 7, 30),
                bd("9.60"), bd("9.70"), bd("10.00"), bd("10.10"), bd("10.20"),
                bd("10.00"), bd("60"), "Tushare cyq_perf", Instant.now());
    }

    private ShortTermChipSnapshot verifiedSnapshot(ExternalChipPerformance external) {
        LocalChipDistribution local = new LocalChipDistributionCalculator(120, 150, 80, bd("0.95"))
                .calculate(bars(), bd("10"), ChipCalculationMode.COMPLETED_BAR);
        ChipVerificationResult verification = new ChipModelVerifier(
                bd("0.03"), bd("0.70"), bd("0.10"), bd("0.60"))
                .verify(local, external, LocalDate.of(2026, 7, 30));
        return new ChipStructureScorer(bd("0.25")).score(local, verification, external);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
