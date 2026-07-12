package com.aistock.research.tradefeedback;

import com.aistock.research.cycle.CycleTrialCandidate;
import com.aistock.research.cycle.CycleTrialReport;
import com.aistock.research.dailysignal.DailyDecisionSignal;
import com.aistock.research.dailysignal.DailySignalReport;
import com.aistock.research.mispricing.MispricedAsset;
import com.aistock.research.mispricing.MispricingReport;
import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanJobStatus;
import com.aistock.research.tech.TechTrackedStock;
import com.aistock.research.tech.TechTrackingReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RecommendationAttestationService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_MAX_MARKET_AGE = Duration.ofDays(7);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final int DEFAULT_MAXIMUM_SIZE = 5_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxMarketAge;
    private final int maximumSize;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, String> tokenByIdentity = new HashMap<>();

    @Autowired
    public RecommendationAttestationService(
            ObjectMapper objectMapper,
            @Value("${trade-feedback.attestation.ttl:PT30M}") Duration ttl,
            @Value("${trade-feedback.attestation.max-market-age:P7D}") Duration maxMarketAge,
            @Value("${trade-feedback.attestation.maximum-size:5000}") int maximumSize
    ) {
        this(objectMapper, Clock.systemUTC(), ttl, maxMarketAge, maximumSize);
    }

    public RecommendationAttestationService(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC(), DEFAULT_TTL, DEFAULT_MAX_MARKET_AGE, DEFAULT_MAXIMUM_SIZE);
    }

    RecommendationAttestationService(
            ObjectMapper objectMapper,
            Clock clock,
            Duration ttl,
            int maximumSize
    ) {
        this(objectMapper, clock, ttl, DEFAULT_MAX_MARKET_AGE, maximumSize);
    }

    RecommendationAttestationService(
            ObjectMapper objectMapper,
            Clock clock,
            Duration ttl,
            Duration maxMarketAge,
            int maximumSize
    ) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("推荐凭证有效期必须为正数");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("推荐凭证容量必须为正整数");
        }
        this.ttl = ttl;
        if (maxMarketAge == null || maxMarketAge.isZero() || maxMarketAge.isNegative()) {
            throw new IllegalArgumentException("行情凭证最大年龄必须为正数");
        }
        this.maxMarketAge = maxMarketAge;
        this.maximumSize = maximumSize;
    }

    public synchronized String register(
            RecommendationSource source,
            String symbol,
            String companyName,
            String recommendationAction,
            BigDecimal recommendationScore,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            Object recommendationPayload
    ) {
        if (source == null) {
            throw new IllegalArgumentException("推荐来源不能为空");
        }
        String canonicalSymbol = required(symbol, "股票代码不能为空");
        if (!canonicalSymbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码必须是 6 位数字");
        }
        String canonicalName = required(companyName, "公司名称不能为空");
        String canonicalAction = required(recommendationAction, "推荐动作不能为空");
        if (recommendedPrice == null || recommendedPrice.signum() <= 0) {
            throw new IllegalArgumentException("推荐价格必须大于零");
        }
        if (recommendedAt == null) {
            throw new IllegalArgumentException("推荐时间不能为空");
        }

        Instant now = clock.instant();
        validateMarketTimestamp(recommendedAt, now);
        purgeExpired(now);
        String payloadJson = serialize(recommendationPayload);
        String identityMaterial = String.join(
                "|",
                source.sourceModule(),
                canonicalSymbol.toUpperCase(Locale.ROOT),
                source.ruleVersion(),
                recommendedAt.toString(),
                canonicalName,
                canonicalAction,
                recommendationScore == null ? "" : recommendationScore.stripTrailingZeros().toPlainString(),
                recommendedPrice.stripTrailingZeros().toPlainString(),
                payloadJson);
        String identity = UUID.nameUUIDFromBytes(identityMaterial.getBytes(StandardCharsets.UTF_8)).toString();
        String existingToken = tokenByIdentity.get(identity);
        if (existingToken != null && entries.containsKey(existingToken)) {
            entries.get(existingToken);
            return existingToken;
        }

        VerifiedRecommendationSnapshot snapshot = new VerifiedRecommendationSnapshot(
                identity,
                canonicalSymbol,
                canonicalName,
                source.sourceModule(),
                canonicalAction,
                recommendationScore,
                source.ruleVersion(),
                recommendedPrice,
                recommendedAt,
                payloadJson);
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(identity, snapshot, now.plus(ttl)));
        tokenByIdentity.put(identity, token);
        evictOverflow();
        return token;
    }

    public ShortTermReport attest(ShortTermReport report) {
        if (report == null || report.generatedAt() == null) {
            return report;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (ShortTermCandidate candidate : safe(report.candidates())) {
            String token = registerIfFactual(
                    RecommendationSource.SHORT_TERM,
                    candidate.symbol(),
                    candidate.name(),
                    firstNonBlank(
                            candidate.todayAdvice() == null ? null : candidate.todayAdvice().actionLabel(),
                            candidate.actionLabel(),
                            candidate.action()),
                    candidate.score() == null ? null : candidate.score().finalScore(),
                    candidate.latestPrice(),
                    candidate.quoteFreshness() == null || candidate.quoteFreshness().blocksRealtimeDecision()
                            ? null
                            : candidate.quoteFreshness().marketTimestamp(),
                    candidate);
            putToken(tokens, candidate.symbol(), token);
        }
        return new ShortTermReport(
                report.scope(), report.universeCount(), report.reviewedCount(), report.klineReviewedCount(),
                report.candidateCount(), report.quoteNote(), report.tradingSession(), report.methodology(),
                report.ruleSet(), report.weightProfile(), report.candidates(), report.hotDirections(),
                report.marketSentiment(), report.exclusions(), Map.copyOf(tokens), report.generatedAt());
    }

    public ShortTermScanJobStatus attest(ShortTermScanJobStatus status) {
        if (status == null || status.report() == null) {
            return status;
        }
        return new ShortTermScanJobStatus(
                status.jobId(), status.status(), status.createdAt(), status.startedAt(), status.finishedAt(),
                status.message(), attest(status.report()));
    }

    public TechTrackingReport attest(TechTrackingReport report) {
        if (report == null || report.generatedAt() == null) {
            return report;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (TechTrackedStock candidate : safe(report.candidates())) {
            String token = registerIfFactual(
                    RecommendationSource.HOT_TRACKER,
                    candidate.symbol(),
                    candidate.name(),
                    firstNonBlank(
                            candidate.todayAdvice() == null ? null : candidate.todayAdvice().actionLabel(),
                            candidate.actionLabel(),
                            candidate.action()),
                    candidate.score() == null ? null : candidate.score().finalScore(),
                    candidate.latestPrice(),
                    candidate.marketTimestamp(),
                    candidate);
            putToken(tokens, candidate.symbol(), token);
        }
        return new TechTrackingReport(
                report.scope(), report.universeCount(), report.candidateCount(), report.quoteNote(),
                report.methodology(), report.policySignals(), report.ruleSet(), report.candidates(),
                Map.copyOf(tokens), report.generatedAt());
    }

    public MispricingReport attest(MispricingReport report) {
        if (report == null || report.generatedAt() == null) {
            return report;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (MispricedAsset candidate : safe(report.candidates())) {
            String token = registerIfFactual(
                    RecommendationSource.MISPRICING,
                    candidate.symbol(),
                    candidate.name(),
                    firstNonBlank(
                            candidate.todayAdvice() == null ? null : candidate.todayAdvice().actionLabel(),
                            candidate.actionLabel(),
                            candidate.action()),
                    candidate.score() == null ? null : candidate.score().finalScore(),
                    candidate.latestPrice(),
                    candidate.marketTimestamp(),
                    candidate);
            putToken(tokens, candidate.symbol(), token);
        }
        return new MispricingReport(
                report.scope(), report.universeCount(), report.candidateCount(), report.quoteNote(),
                report.methodology(), report.styleHeat(), report.ruleSet(), report.policySignals(),
                report.candidates(), Map.copyOf(tokens), report.generatedAt());
    }

    public CycleTrialReport attest(CycleTrialReport report) {
        if (report == null || report.generatedAt() == null) {
            return report;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (CycleTrialCandidate candidate : safe(report.candidates())) {
            String token = registerIfFactual(
                    RecommendationSource.CYCLE_TRIAL,
                    candidate.symbol(),
                    candidate.name(),
                    firstNonBlank(
                            candidate.todayAdvice() == null ? null : candidate.todayAdvice().actionLabel(),
                            candidate.actionLabel(),
                            candidate.action()),
                    candidate.score() == null ? null : candidate.score().finalScore(),
                    candidate.latestPrice(),
                    candidate.marketTimestamp(),
                    candidate);
            putToken(tokens, candidate.symbol(), token);
        }
        return new CycleTrialReport(
                report.scope(), report.universeCount(), report.candidateCount(), report.quoteNote(),
                report.methodology(), report.ruleSet(), report.candidates(), Map.copyOf(tokens), report.generatedAt());
    }

    public DailySignalReport attest(DailySignalReport report) {
        if (report == null) {
            return null;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (DailyDecisionSignal signal : safe(report.signals())) {
            String token = registerIfFactual(
                    RecommendationSource.DAILY_SIGNAL,
                    signal.symbol(),
                    signal.name(),
                    firstNonBlank(signal.actionLabel(), signal.action()),
                    signal.score(),
                    signal.recommendedPrice(),
                    signal.marketTimestamp(),
                    signal);
            putToken(tokens, dailySignalKey(signal), token);
        }
        return new DailySignalReport(
                report.scope(), report.sourceProject(), report.sourceCommit(), report.marketContext(),
                report.actionCounts(), report.strategyPlaybooks(), report.signals(), Map.copyOf(tokens),
                report.generatedAt());
    }

    public synchronized VerifiedRecommendationSnapshot require(String token) {
        String normalized = required(token, "推荐凭证不能为空");
        Entry entry = entries.get(normalized);
        if (entry == null) {
            throw new IllegalArgumentException("推荐凭证无效或未由当前推荐响应签发");
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            remove(normalized, entry);
            throw new IllegalArgumentException("推荐凭证已过期，请刷新推荐后重试");
        }
        return entry.snapshot();
    }

    synchronized int size() {
        purgeExpired(clock.instant());
        return entries.size();
    }

    private String registerIfFactual(
            RecommendationSource source,
            String symbol,
            String companyName,
            String action,
            BigDecimal score,
            BigDecimal price,
            Instant recommendedAt,
            Object payload
    ) {
        if (price == null || price.signum() <= 0 || !isMarketTimestampUsable(recommendedAt, clock.instant())) {
            return null;
        }
        return register(source, symbol, companyName, action, score, price, recommendedAt, payload);
    }

    private boolean isMarketTimestampUsable(Instant marketTimestamp, Instant now) {
        return marketTimestamp != null
                && !marketTimestamp.isAfter(now.plus(MAX_FUTURE_SKEW))
                && !marketTimestamp.isBefore(now.minus(maxMarketAge));
    }

    private void validateMarketTimestamp(Instant marketTimestamp, Instant now) {
        if (marketTimestamp.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new IllegalArgumentException("行情时间明显晚于当前时间");
        }
        if (marketTimestamp.isBefore(now.minus(maxMarketAge))) {
            throw new IllegalArgumentException("行情时间已超过凭证新鲜度边界");
        }
    }

    private void putToken(Map<String, String> tokens, String key, String token) {
        if (key != null && !key.isBlank() && token != null) {
            tokens.put(key, token);
        }
    }

    private String dailySignalKey(DailyDecisionSignal signal) {
        return firstNonBlank(signal.sourceType(), "unknown") + "|" + signal.symbol();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "未标注动作";
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("服务端推荐快照无法序列化", exception);
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> candidate = iterator.next();
            if (!now.isBefore(candidate.getValue().expiresAt())) {
                tokenByIdentity.remove(candidate.getValue().identity(), candidate.getKey());
                iterator.remove();
            }
        }
    }

    private void evictOverflow() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maximumSize && iterator.hasNext()) {
            Map.Entry<String, Entry> eldest = iterator.next();
            tokenByIdentity.remove(eldest.getValue().identity(), eldest.getKey());
            iterator.remove();
        }
    }

    private void remove(String token, Entry entry) {
        entries.remove(token);
        tokenByIdentity.remove(entry.identity(), token);
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private record Entry(String identity, VerifiedRecommendationSnapshot snapshot, Instant expiresAt) {
    }
}
