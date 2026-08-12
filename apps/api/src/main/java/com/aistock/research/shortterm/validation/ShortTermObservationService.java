package com.aistock.research.shortterm.validation;

import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermMarketRegime;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShortTermObservationService {

    private final ShortTermObservationWriter writer;
    private final TradingClockService tradingClock;
    private final ShortTermValidationSettings settings;
    private final ObjectMapper objectMapper;

    public ShortTermObservationService(
            ShortTermObservationWriter writer,
            TradingClockService tradingClock,
            ShortTermValidationSettings settings,
            ObjectMapper objectMapper
    ) {
        this.writer = writer;
        this.tradingClock = tradingClock;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int captureScheduledFinal(String publicationKey, ShortTermReport report, Instant publishedAt) {
        return capture(
                publicationKey,
                "SCHEDULED_FINAL",
                report,
                ShortTermSnapshotStatus.FINAL_READY,
                List.of(),
                publishedAt
        );
    }

    @Transactional
    public int captureManual(
            String publicationKey,
            ShortTermReport report,
            ShortTermSnapshotStatus resultStatus,
            List<String> blockedReasons,
            Instant publishedAt
    ) {
        return capture(
                publicationKey,
                "MANUAL_SCAN",
                report,
                resultStatus,
                blockedReasons,
                publishedAt
        );
    }

    private synchronized int capture(
            String publicationKey,
            String publicationType,
            ShortTermReport report,
            ShortTermSnapshotStatus resultStatus,
            List<String> blockedReasons,
            Instant publishedAt
    ) {
        if (publicationKey == null || publicationKey.isBlank() || report == null
                || report.candidates() == null || report.candidates().isEmpty()) {
            return 0;
        }
        Instant capturedAt = publishedAt == null ? Instant.now() : publishedAt;
        int created = 0;
        for (ShortTermCandidate candidate : report.candidates()) {
            if (candidate == null || candidate.symbol() == null || candidate.symbol().isBlank()) {
                continue;
            }
            String observationId = digest(publicationKey + "|" + candidate.symbol());
            if (writer.exists(observationId)) {
                continue;
            }
            LocalDate tradeDate = recommendationTradeDate(report, candidate);
            OutcomeTargets targets = outcomeTargets(tradeDate);
            ObservationEligibility eligibility = eligibility(
                    report, candidate, resultStatus, blockedReasons, capturedAt, targets);
            String signalFamily = candidate.signalProfile() == null
                    ? "UNAVAILABLE" : text(candidate.signalProfile().primaryFamily(), "UNAVAILABLE");
            ShortTermMarketRegime regime = report.marketRegime();
            String regimeState = regime == null ? "UNAVAILABLE" : text(regime.state(), "UNAVAILABLE");
            ShortTermCoverageSnapshot coverage = report.coverage();
            boolean calibrationEligible = eligibility.eligible()
                    && "SCHEDULED_FINAL".equals(publicationType);
            ShortTermSignalObservationEntity entity = new ShortTermSignalObservationEntity(
                    observationId,
                    publicationKey,
                    publicationType,
                    RecommendationSource.SHORT_TERM.ruleVersion(),
                    candidate.symbol(),
                    text(candidate.name(), candidate.symbol()),
                    candidate.rank(),
                    candidate.todayAdvice() == null
                            ? text(candidate.action(), "WAIT")
                            : text(candidate.todayAdvice().action(), text(candidate.action(), "WAIT")),
                    signalFamily,
                    regimeState,
                    candidate.latestPrice(),
                    tradeDate,
                    report.dataCutoffAt(),
                    capturedAt,
                    coverage == null ? "UNKNOWN" : text(coverage.source(), "UNKNOWN"),
                    coverage == null ? null : coverage.coverageRatio(),
                    coverage == null ? 0 : coverage.expectedCount(),
                    coverage == null ? 0 : coverage.fetchedCount(),
                    eligibility.eligible(),
                    calibrationEligible,
                    eligibility.blockReason(),
                    payload(publicationType, resultStatus, blockedReasons, report, candidate),
                    settings.costAssumptions(),
                    capturedAt
            );
            List<ShortTermSignalOutcomeEntity> outcomeSlots = List.of();
            if (eligibility.eligible() && targets.available()) {
                outcomeSlots = List.of(
                        ShortTermSignalOutcomeEntity.pending(
                                digest(observationId + "|T1"), observationId, "T1",
                                targets.t1(), capturedAt),
                        ShortTermSignalOutcomeEntity.pending(
                                digest(observationId + "|T2"), observationId, "T2",
                                targets.t2(), capturedAt)
                );
            }
            try {
                if (writer.persistIfAbsent(entity, outcomeSlots)) {
                    created++;
                }
            } catch (DataIntegrityViolationException conflict) {
                if (!writer.exists(observationId)) {
                    throw conflict;
                }
            }
        }
        return created;
    }

    private ObservationEligibility eligibility(
            ShortTermReport report,
            ShortTermCandidate candidate,
            ShortTermSnapshotStatus resultStatus,
            List<String> sourceBlockedReasons,
            Instant publishedAt,
            OutcomeTargets targets
    ) {
        List<String> reasons = new ArrayList<>();
        if (resultStatus != ShortTermSnapshotStatus.FINAL_READY) {
            reasons.add(resultStatus == null ? "RESULT_STATUS_MISSING" : resultStatus.name());
            if (sourceBlockedReasons != null) {
                reasons.addAll(sourceBlockedReasons);
            }
        }
        ShortTermCoverageSnapshot coverage = report.coverage();
        if (coverage == null || !coverage.executionReliable()
                || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(new BigDecimal("0.95")) < 0) {
            reasons.add("COVERAGE_BELOW_95");
        }
        if (report.dataCutoffAt() == null) {
            reasons.add("CUTOFF_MISSING");
        }
        if (candidate.latestPrice() == null || candidate.latestPrice().signum() <= 0) {
            reasons.add("BASELINE_PRICE_MISSING");
        }
        LocalDate tradeDate = recommendationTradeDate(report, candidate);
        if (tradeDate == null) {
            reasons.add("RECOMMENDATION_TRADE_DATE_MISSING");
        }
        LocalDate cutoffTradeDate = report.dataCutoffAt() == null ? null
                : report.dataCutoffAt().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate();
        LocalDate quoteTradeDate = candidate.quoteFreshness() == null
                ? null : candidate.quoteFreshness().tradeDate();
        if (quoteTradeDate == null || cutoffTradeDate == null || !quoteTradeDate.equals(cutoffTradeDate)) {
            reasons.add("QUOTE_TRADE_DATE_MISMATCH");
        }
        LocalDate publicationTradeDate = publishedAt == null ? null
                : publishedAt.atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate();
        if (cutoffTradeDate == null || publicationTradeDate == null
                || !publicationTradeDate.equals(cutoffTradeDate)) {
            reasons.add("PUBLICATION_TRADE_DATE_MISMATCH");
        }
        if (candidate.quoteFreshness() != null
                && candidate.quoteFreshness().marketTimestamp() != null
                && report.dataCutoffAt() != null
                && candidate.quoteFreshness().marketTimestamp().isAfter(report.dataCutoffAt())) {
            reasons.add("QUOTE_AFTER_REPORT_CUTOFF");
        }
        String family = candidate.signalProfile() == null ? null : candidate.signalProfile().primaryFamily();
        if (family == null || family.isBlank() || "UNAVAILABLE".equals(family)) {
            reasons.add("SIGNAL_FAMILY_UNAVAILABLE");
        }
        if (report.marketRegime() == null || "UNAVAILABLE".equals(report.marketRegime().state())) {
            reasons.add("MARKET_REGIME_UNAVAILABLE");
        }
        if (targets == null || !targets.available()) {
            reasons.add("TRADING_CALENDAR_UNAVAILABLE");
        }
        return new ObservationEligibility(reasons.isEmpty(), String.join(";", reasons));
    }

    private OutcomeTargets outcomeTargets(LocalDate tradeDate) {
        if (tradeDate == null) {
            return OutcomeTargets.unavailable();
        }
        return new OutcomeTargets(
                tradingClock.verifiedTradingDayAfter(tradeDate, 1).orElse(null),
                tradingClock.verifiedTradingDayAfter(tradeDate, 2).orElse(null)
        );
    }

    private LocalDate recommendationTradeDate(ShortTermReport report, ShortTermCandidate candidate) {
        if (candidate.quoteFreshness() != null && candidate.quoteFreshness().tradeDate() != null) {
            return candidate.quoteFreshness().tradeDate();
        }
        if (candidate.technical() != null && candidate.technical().tradeDate() != null) {
            return candidate.technical().tradeDate();
        }
        return report.dataCutoffAt() == null ? null
                : report.dataCutoffAt().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate();
    }

    private String payload(
            String publicationType,
            ShortTermSnapshotStatus resultStatus,
            List<String> blockedReasons,
            ShortTermReport report,
            ShortTermCandidate candidate
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("publicationType", publicationType);
        snapshot.put("resultStatus", resultStatus == null ? null : resultStatus.name());
        snapshot.put("blockedReasons", blockedReasons == null ? List.of() : List.copyOf(blockedReasons));
        snapshot.put("dataCutoffAt", report.dataCutoffAt());
        snapshot.put("coverage", report.coverage());
        snapshot.put("technicalReviewCoverage", report.technicalReviewCoverage());
        snapshot.put("marketRegime", report.marketRegime());
        snapshot.put("candidate", candidate);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("短线观察快照序列化失败", exception);
        }
    }

    private String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ObservationEligibility(boolean eligible, String blockReason) {
    }

    private record OutcomeTargets(LocalDate t1, LocalDate t2) {
        private static OutcomeTargets unavailable() {
            return new OutcomeTargets(null, null);
        }

        private boolean available() {
            return t1 != null && t2 != null;
        }
    }
}
