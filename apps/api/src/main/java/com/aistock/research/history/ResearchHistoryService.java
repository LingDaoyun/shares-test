package com.aistock.research.history;

import com.aistock.research.decision.InvestmentDecisionReport;
import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ResearchHistoryService {

    private static final String RULE_VERSION = "investment-decision-v1";
    private static final String SHORT_TERM_RULE_VERSION = "short-term-right-side-v2";

    private final AnalysisHistoryRepository analysisRepository;
    private final DecisionHistoryRepository decisionRepository;
    private final ObjectMapper objectMapper;

    public ResearchHistoryService(
            AnalysisHistoryRepository analysisRepository,
            DecisionHistoryRepository decisionRepository,
            ObjectMapper objectMapper
    ) {
        this.analysisRepository = analysisRepository;
        this.decisionRepository = decisionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DecisionHistoryEntry recordDecision(InvestmentDecisionReport report, String sourceType) {
        Instant recordedAt = Instant.now();
        Instant dataAsOf = report.generatedAt() == null ? recordedAt : report.generatedAt();
        String payload = serialize(report);
        String analysisId = UUID.randomUUID().toString();
        String provider = report.consensus() == null ? null : report.consensus().aiProvider();
        String model = report.consensus() == null ? null : report.consensus().aiModel();
        AnalysisHistoryEntity analysis = new AnalysisHistoryEntity(
                analysisId,
                report.symbol(),
                report.companyName(),
                "INVESTMENT_DECISION",
                report.actionStage(),
                report.actionReason(),
                provider,
                model,
                payload,
                dataAsOf,
                recordedAt
        );
        analysisRepository.save(analysis);

        DecisionHistoryEntity decision = new DecisionHistoryEntity(
                UUID.randomUUID().toString(),
                analysisId,
                report.symbol(),
                sourceType == null || sourceType.isBlank() ? "MANUAL" : sourceType,
                report.actionStage(),
                report.actionLabel(),
                report.decisionScore(),
                RULE_VERSION,
                payload,
                dataAsOf,
                recordedAt
        );
        decisionRepository.save(decision);
        return decision.toEntry();
    }

    @Transactional
    public void recordShortTermReport(ShortTermReport report) {
        if (report == null || report.candidates() == null || report.candidates().isEmpty()) {
            return;
        }
        Instant recordedAt = Instant.now();
        for (ShortTermCandidate candidate : report.candidates()) {
            if (candidate == null || candidate.symbol() == null || candidate.symbol().isBlank()) {
                continue;
            }
            Instant dataAsOf = shortTermDataAsOf(candidate, report, recordedAt);
            ShortTermHistoryPayload historyPayload = new ShortTermHistoryPayload(
                    candidate,
                    report.marketSentiment(),
                    report.hotDirections(),
                    report.ruleSet(),
                    report.tradingSession(),
                    report.quoteNote(),
                    report.universeCount(),
                    report.reviewedCount(),
                    report.klineReviewedCount(),
                    report.generatedAt()
            );
            String payload = serialize(historyPayload);
            String analysisId = UUID.randomUUID().toString();
            String analysisStatus = text(candidate.action(), "WAIT_CONFIRM");
            String analysisSummary = text(candidate.reason(), "短线候选已生成，等待证据复核。");
            analysisRepository.save(new AnalysisHistoryEntity(
                    analysisId,
                    candidate.symbol(),
                    text(candidate.name(), candidate.symbol()),
                    "SHORT_TERM_RIGHT_SIDE",
                    analysisStatus,
                    analysisSummary,
                    null,
                    null,
                    payload,
                    dataAsOf,
                    recordedAt
            ));

            String actionStage = candidate.todayAdvice() == null
                    ? analysisStatus
                    : text(candidate.todayAdvice().action(), analysisStatus);
            String actionLabel = candidate.todayAdvice() == null
                    ? text(candidate.actionLabel(), "等待确认")
                    : text(candidate.todayAdvice().actionLabel(), text(candidate.actionLabel(), "等待确认"));
            decisionRepository.save(new DecisionHistoryEntity(
                    UUID.randomUUID().toString(),
                    analysisId,
                    candidate.symbol(),
                    "SHORT_TERM_SCAN",
                    actionStage,
                    actionLabel,
                    candidate.score() == null ? null : candidate.score().finalScore(),
                    SHORT_TERM_RULE_VERSION,
                    payload,
                    dataAsOf,
                    recordedAt
            ));
        }
    }

    private Instant shortTermDataAsOf(
            ShortTermCandidate candidate,
            ShortTermReport report,
            Instant fallback
    ) {
        if (candidate.quoteFreshness() != null && candidate.quoteFreshness().marketTimestamp() != null) {
            return candidate.quoteFreshness().marketTimestamp();
        }
        return report.generatedAt() == null ? fallback : report.generatedAt();
    }

    @Transactional(readOnly = true)
    public List<DecisionHistoryEntry> decisions(String symbol, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return decisionRepository.findTop50BySymbolOrderByRecordedAtDesc(symbol).stream()
                .limit(safeLimit)
                .map(DecisionHistoryEntity::toEntry)
                .toList();
    }

    private String serialize(Object report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("投资决策历史序列化失败", exception);
        }
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
