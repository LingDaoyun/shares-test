package com.aistock.research.tradefeedback;

import com.aistock.research.market.MarketScanCandidate;
import com.aistock.research.market.MarketScanReport;
import com.aistock.research.market.MarketScanScoreBreakdown;
import com.aistock.research.tech.TechTrackedStock;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermCrossSectionContext;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermTechnicalReviewCoverage;
import com.aistock.research.trading.TradingAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationAttestationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-12T04:00:00Z");

    @Test
    void resolvesOnlyTheCanonicalSnapshotRegisteredByARecommendationResponse() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        String token = service.register(
                RecommendationSource.MISPRICING,
                "002714",
                "牧原股份",
                "分批建仓",
                new BigDecimal("78"),
                new BigDecimal("36.20"),
                NOW.minusSeconds(30),
                Map.of("server", "canonical"));

        VerifiedRecommendationSnapshot snapshot = service.require(token);

        assertThat(snapshot.symbol()).isEqualTo("002714");
        assertThat(snapshot.sourceModule()).isEqualTo("MISPRICING");
        assertThat(snapshot.ruleVersion()).isEqualTo("mispricing-v2");
        assertThat(snapshot.recommendationAction()).isEqualTo("分批建仓");
        assertThat(snapshot.recommendedPrice()).isEqualByComparingTo("36.20");
        assertThat(snapshot.recommendedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(snapshot.recommendationPayloadJson()).containsOnlyOnce("canonical");
    }

    @Test
    void rejectsUnknownAndExpiredAttestations() {
        MutableClock clock = new MutableClock(NOW);
        RecommendationAttestationService service = new RecommendationAttestationService(
                objectMapper(), clock, Duration.ofSeconds(1), 8);
        String token = service.register(
                RecommendationSource.SHORT_TERM,
                "600519",
                "贵州茅台",
                "观察",
                new BigDecimal("70"),
                new BigDecimal("1500"),
                NOW,
                Map.of());
        clock.advance(Duration.ofSeconds(2));

        assertThatThrownBy(() -> service.require("not-issued-by-server"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("推荐凭证");
        assertThatThrownBy(() -> service.require(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void evictsOldEntriesWhenTheRegistryReachesItsBound() {
        RecommendationAttestationService service = registry(2, Duration.ofMinutes(30));
        String first = register(service, RecommendationSource.SHORT_TERM, "600001");
        register(service, RecommendationSource.HOT_TRACKER, "600002");
        register(service, RecommendationSource.CYCLE_TRIAL, "600003");

        assertThat(service.size()).isEqualTo(2);
        assertThatThrownBy(() -> service.require(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("推荐凭证");
    }

    @Test
    void reissuesANewTokenWithTheSameStableSnapshotIdentityAfterExpiry() {
        MutableClock clock = new MutableClock(NOW);
        RecommendationAttestationService service = new RecommendationAttestationService(
                objectMapper(), clock, Duration.ofSeconds(1), 8);
        String firstToken = register(service, RecommendationSource.MISPRICING, "002714");
        String firstIdentity = service.require(firstToken).attestationId();
        clock.advance(Duration.ofSeconds(2));

        String secondToken = register(service, RecommendationSource.MISPRICING, "002714");

        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(service.require(secondToken).attestationId()).isEqualTo(firstIdentity);
    }

    @Test
    void bindsTheAttestationToThePriceMarketTimestampInsteadOfReportGenerationTime() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        Instant marketTimestamp = NOW.minusSeconds(45);
        TechTrackedStock stock = stock("600519", marketTimestamp);
        TechTrackingReport report = new TechTrackingReport(
                "全 A", 1, 1, "实时行情", List.of(), List.of(), null,
                List.of(stock), NOW);

        TechTrackingReport attested = service.attest(report);
        String token = attested.tradeCaptureTokens().get("600519");

        assertThat(token).isNotBlank();
        assertThat(service.require(token).recommendedAt()).isEqualTo(marketTimestamp);
    }

    @Test
    void refusesToAttestMissingStaleOrFutureMarketTimestamps() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        TechTrackingReport report = new TechTrackingReport(
                "全 A", 3, 3, "混合行情", List.of(), List.of(), null,
                List.of(
                        stock("600001", null),
                        stock("600002", NOW.minus(Duration.ofDays(8))),
                        stock("600003", NOW.plus(Duration.ofMinutes(6)))
                ),
                NOW);

        TechTrackingReport attested = service.attest(report);

        assertThat(attested.tradeCaptureTokens()).isEmpty();
    }

    @Test
    void preservesShortTermCoverageAndCutoffWhenAddingAttestations() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        ShortTermCoverageSnapshot coverage = new ShortTermCoverageSnapshot(
                100,
                95,
                5,
                new BigDecimal("0.9500"),
                true,
                "实时行情",
                NOW.minusSeconds(10)
        );
        ShortTermTechnicalReviewCoverage technicalCoverage = ShortTermTechnicalReviewCoverage.of(20, 10, 9);
        ShortTermCrossSectionContext crossSectionContext = new ShortTermCrossSectionContext(
                95, 12, 9, "点时全市场", List.of("同行样本偏少")
        );
        ShortTermReport report = new ShortTermReport(
                "全市场短线",
                95,
                2,
                2,
                0,
                "测试",
                null,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                coverage,
                List.of("600001", "600002"),
                NOW.minusSeconds(20),
                NOW,
                technicalCoverage,
                crossSectionContext
        );

        ShortTermReport attested = service.attest(report);

        assertThat(attested.coverage()).isEqualTo(coverage);
        assertThat(attested.reviewedSymbols()).containsExactly("600001", "600002");
        assertThat(attested.dataCutoffAt()).isEqualTo(NOW.minusSeconds(20));
        assertThat(attested.technicalReviewCoverage()).isEqualTo(technicalCoverage);
        assertThat(attested.crossSectionContext()).isEqualTo(crossSectionContext);
    }

    @Test
    void attestsLongTermMarketScanCandidatesWithLongTermSourceMetadata() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        Instant marketTimestamp = NOW.minusSeconds(60);
        MarketScanReport report = marketScanReport(List.of(marketCandidate(
                "600987",
                "航民股份",
                new BigDecimal("7.20"),
                marketTimestamp
        )));

        MarketScanReport attested = service.attest(report);
        String token = attested.tradeCaptureTokens().get("600987");
        VerifiedRecommendationSnapshot snapshot = service.require(token);

        assertThat(token).isNotBlank();
        assertThat(snapshot.symbol()).isEqualTo("600987");
        assertThat(snapshot.companyName()).isEqualTo("航民股份");
        assertThat(snapshot.sourceModule()).isEqualTo("LONG_TERM");
        assertThat(snapshot.ruleVersion()).isEqualTo("long-term-value-v1");
        assertThat(snapshot.recommendationAction()).isEqualTo("分批加仓");
        assertThat(snapshot.recommendationScore()).isEqualByComparingTo("88");
        assertThat(snapshot.recommendedPrice()).isEqualByComparingTo("7.20");
        assertThat(snapshot.recommendedAt()).isEqualTo(marketTimestamp);
        assertThat(snapshot.recommendationPayloadJson()).contains("长期现金流稳健");
    }

    @Test
    void skipsLongTermAttestationWhenPriceOrTimestampIsNotUsable() {
        RecommendationAttestationService service = registry(8, Duration.ofMinutes(30));
        MarketScanReport report = marketScanReport(List.of(
                marketCandidate("600001", "缺价格", null, NOW.minusSeconds(60)),
                marketCandidate("600002", "旧行情", new BigDecimal("10.00"), NOW.minus(Duration.ofDays(8))),
                marketCandidate("600003", "未来行情", new BigDecimal("10.00"), NOW.plus(Duration.ofMinutes(6)))
        ));

        MarketScanReport attested = service.attest(report);

        assertThat(attested.tradeCaptureTokens()).isEmpty();
    }

    private TechTrackedStock stock(String symbol, Instant marketTimestamp) {
        return new TechTrackedStock(
                1, symbol, symbol, "HOT", "热门", "测试行业",
                new BigDecimal("10.00"), marketTimestamp, BigDecimal.ZERO,
                null, null, new BigDecimal("100000000"), null,
                "WATCH", "观察", "测试", null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private MarketScanReport marketScanReport(List<MarketScanCandidate> candidates) {
        return new MarketScanReport(
                "长线价投",
                candidates.size(),
                candidates.size(),
                candidates.size(),
                "实时行情",
                null,
                List.of(),
                null,
                List.of(),
                candidates,
                List.of(),
                Map.of(),
                NOW
        );
    }

    private MarketScanCandidate marketCandidate(
            String symbol,
            String name,
            BigDecimal latestPrice,
            Instant marketTimestamp
    ) {
        return new MarketScanCandidate(
                1,
                symbol,
                name,
                "沪市",
                "纺织制造",
                latestPrice,
                marketTimestamp,
                BigDecimal.ZERO,
                new BigDecimal("11.80"),
                new BigDecimal("1.02"),
                new BigDecimal("90000000"),
                null,
                null,
                new MarketScanScoreBreakdown(
                        new BigDecimal("82"),
                        new BigDecimal("70"),
                        new BigDecimal("64"),
                        new BigDecimal("88"),
                        new BigDecimal("75"),
                        new BigDecimal("88")
                ),
                "VALUE_RESEARCH",
                "价值研究",
                "长期现金流稳健",
                new TradingAdvice(
                        "ADD",
                        "分批加仓",
                        82,
                        "长期估值和质量证据支持分批加仓",
                        List.of("长期现金流稳健"),
                        List.of("分批执行")
                ),
                List.of("价值观察"),
                List.of("长期现金流稳健"),
                List.of("公告反证待复核"),
                List.of(),
                null,
                null,
                List.of()
        );
    }

    private String register(
            RecommendationAttestationService service,
            RecommendationSource source,
            String symbol
    ) {
        return service.register(
                source, symbol, symbol, "观察", new BigDecimal("60"), new BigDecimal("10"), NOW, Map.of());
    }

    private RecommendationAttestationService registry(int maximumSize, Duration ttl) {
        return new RecommendationAttestationService(
                objectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), ttl, maximumSize);
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
