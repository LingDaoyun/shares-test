package com.aistock.research.tradefeedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradeOutcomeService {

    private static final Logger logger = LoggerFactory.getLogger(TradeOutcomeService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String RECOMMENDATION = "RECOMMENDATION";
    private static final String EXECUTION = "EXECUTION";
    private static final String DAILY_KLINE = "DAILY_KLINE";
    private static final String LAST_KLINE_CLOSE_FALLBACK = "LAST_KLINE_CLOSE_FALLBACK";
    private static final String EXECUTION_FILLS = "EXECUTION_FILLS";

    private final TradeCaseRepository caseRepository;
    private final TradeFillRepository fillRepository;
    private final TradeOutcomeRepository outcomeRepository;
    private final TradeMarketDataGateway gateway;
    private final TradeOutcomeCalculator outcomeCalculator;
    private final TradeLedgerCalculator ledgerCalculator;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    @Autowired
    public TradeOutcomeService(
            TradeCaseRepository caseRepository,
            TradeFillRepository fillRepository,
            TradeOutcomeRepository outcomeRepository,
            TradeMarketDataGateway gateway,
            TradeOutcomeCalculator outcomeCalculator,
            TradeLedgerCalculator ledgerCalculator,
            PlatformTransactionManager transactionManager
    ) {
        this(caseRepository, fillRepository, outcomeRepository, gateway, outcomeCalculator, ledgerCalculator,
                transactionManager, Clock.systemUTC());
    }

    TradeOutcomeService(
            TradeCaseRepository caseRepository,
            TradeFillRepository fillRepository,
            TradeOutcomeRepository outcomeRepository,
            TradeMarketDataGateway gateway,
            TradeOutcomeCalculator outcomeCalculator,
            TradeLedgerCalculator ledgerCalculator,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.caseRepository = caseRepository;
        this.fillRepository = fillRepository;
        this.outcomeRepository = outcomeRepository;
        this.gateway = gateway;
        this.outcomeCalculator = outcomeCalculator;
        this.ledgerCalculator = ledgerCalculator;
        this.clock = clock;
        this.readTransaction = transaction(transactionManager, true);
        this.writeTransaction = transaction(transactionManager, false);
    }

    public TradeOutcomeRefresh refresh(String caseId) {
        CaseFacts facts = readTransaction.execute(status -> loadFacts(caseId));
        if (facts == null) {
            throw new IllegalStateException("复盘事实读取失败");
        }

        MarketFacts market;
        try {
            market = loadMarketFacts(facts);
        } catch (RuntimeException exception) {
            logger.warn("复盘行情刷新失败：{}，原因：{}", caseId, exception.getMessage());
            return new TradeOutcomeRefresh(List.of("行情刷新失败：" + rootMessage(exception)));
        }

        List<ScopedOutcome> calculated = calculate(facts, market);
        Boolean written = writeTransaction.execute(status -> upsert(facts, calculated));
        List<String> warnings = new ArrayList<>(market.warnings());
        if (!Boolean.TRUE.equals(written)) {
            warnings.add("复盘事实已变更，本次刷新未写入");
        }
        return new TradeOutcomeRefresh(List.copyOf(warnings));
    }

    public void refreshOpenCases() {
        List<TradeCaseEntity> cases = readTransaction.execute(status -> caseRepository.findAllByOrderByCreatedAtDesc());
        if (cases == null) {
            return;
        }
        for (TradeCaseEntity tradeCase : cases) {
            try {
                if (TradeCaseStatus.CANCELLED.name().equals(tradeCase.getStatus()) || !needsRefresh(tradeCase)) {
                    continue;
                }
                refresh(tradeCase.getCaseId());
            } catch (RuntimeException exception) {
                logger.warn("定时刷新单个复盘失败：{}，原因：{}", tradeCase.getCaseId(), exception.getMessage());
            }
        }
    }

    public List<TradeOutcomeEntity> outcomes(String caseId) {
        return Optional.ofNullable(readTransaction.execute(status -> {
            requireCase(caseId);
            return outcomeRepository.findByCaseIdOrderByHorizonAsc(caseId);
        })).orElseGet(List::of);
    }

    public List<TradeOutcomeEntity> outcomes(Collection<String> caseIds) {
        List<String> normalizedCaseIds = caseIds == null ? List.of() : caseIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(caseId -> !caseId.isEmpty())
                .distinct()
                .toList();
        if (normalizedCaseIds.isEmpty()) {
            return List.of();
        }
        return Optional.ofNullable(readTransaction.execute(status ->
                outcomeRepository.findByCaseIdInOrderByCaseIdAscBaselineTypeAscHorizonAsc(normalizedCaseIds)
        )).orElseGet(List::of);
    }

    private boolean needsRefresh(TradeCaseEntity tradeCase) {
        if (TradeCaseStatus.HOLDING.name().equals(tradeCase.getStatus())
                || TradeCaseStatus.PLANNED.name().equals(tradeCase.getStatus())) {
            return true;
        }
        Optional<TradeOutcomeEntity> t20 = readTransaction.execute(status ->
                outcomeRepository.findByCaseIdAndBaselineTypeAndHorizon(
                        tradeCase.getCaseId(), RECOMMENDATION, "T20"));
        return t20 == null || t20.isEmpty() || "PENDING".equals(t20.get().getStatus());
    }

    private CaseFacts loadFacts(String caseId) {
        TradeCaseEntity tradeCase = requireCase(caseId);
        List<FillFact> fills = fillFacts(caseId);
        boolean hadExecutionOutcomes = outcomeRepository
                .findByCaseIdAndBaselineTypeAndHorizon(caseId, EXECUTION, "CURRENT").isPresent()
                || outcomeRepository.findByCaseIdAndBaselineTypeAndHorizon(caseId, EXECUTION, "CLOSED").isPresent();
        return new CaseFacts(
                tradeCase.getCaseId(),
                tradeCase.getSymbol(),
                tradeCase.getRecommendedPrice(),
                tradeCase.getRecommendedAt(),
                fills,
                hadExecutionOutcomes,
                new FactsVersion(tradeCase.getUpdatedAt(), fills));
    }

    private MarketFacts loadMarketFacts(CaseFacts facts) {
        LocalDate recommendationDate = facts.recommendedAt().atZone(SHANGHAI).toLocalDate();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        List<MarketBar> rows = gateway.dailyKLines(facts.symbol(), recommendationDate, today);
        Optional<LatestMarketPrice> latest = gateway.latestPrice(facts.symbol());
        List<String> warnings = new ArrayList<>();
        if (latest.filter(this::trustworthyQuote).isPresent()) {
            if (EastMoneyTradeMarketDataGateway.TENCENT_LIVE_QUOTE_FALLBACK.equals(latest.get().source())) {
                warnings.add("CURRENT 使用 " + latest.get().source());
            }
            return new MarketFacts(
                    List.copyOf(rows), latest.get().price(), latest.get().tradeDate(), latest.get().source(),
                    latest.get().marketTimestamp(), warnings);
        }
        if (latest.isPresent()) {
            warnings.add("CURRENT 行情缺少可信交易时间，未按实时行情使用");
        }

        Optional<MarketBar> lastBar = rows.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null
                        && row.close().compareTo(BigDecimal.ZERO) > 0)
                .max(java.util.Comparator.comparing(MarketBar::tradeDate));
        if (lastBar.isPresent()) {
            warnings.add("CURRENT 使用 LAST_KLINE_CLOSE_FALLBACK");
            return new MarketFacts(
                    List.copyOf(rows), lastBar.get().close(), lastBar.get().tradeDate(),
                    LAST_KLINE_CLOSE_FALLBACK, null, warnings);
        }
        return new MarketFacts(List.copyOf(rows), null, null, null, null, warnings);
    }

    private boolean trustworthyQuote(LatestMarketPrice latest) {
        return latest.price() != null
                && latest.price().signum() > 0
                && latest.tradeDate() != null
                && latest.marketTimestamp() != null
                && latest.tradeDate().equals(latest.marketTimestamp().atZone(SHANGHAI).toLocalDate());
    }

    private List<ScopedOutcome> calculate(CaseFacts facts, MarketFacts market) {
        List<ScopedOutcome> results = new ArrayList<>();
        outcomeCalculator.evaluateRecommendation(
                        facts.recommendedPrice(), market.rows(), facts.recommendedAt())
                .forEach(result -> results.add(new ScopedOutcome(RECOMMENDATION, result, DAILY_KLINE, null)));
        results.add(new ScopedOutcome(RECOMMENDATION, outcomeCalculator.evaluateRecommendationCurrent(
                facts.recommendedPrice(), market.rows(), facts.recommendedAt(),
                market.latestPrice(), market.evaluationDate()), market.sourceName(), market.marketTimestamp()));

        if (!facts.fills().isEmpty() || facts.hadExecutionOutcomes()) {
            if (facts.fills().isEmpty()) {
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CURRENT"), null, null));
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CLOSED"), null, null));
                return results;
            }
            TradeLedgerSummary ledger = ledgerCalculator.calculate(ledgerFills(facts.fills()), market.latestPrice());
            if (ledger.positionQuantity() == 0) {
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CURRENT"), null, null));
                results.add(new ScopedOutcome(EXECUTION, outcomeCalculator.evaluateExecution(
                        ledgerFills(facts.fills()), 0, null, market.evaluationDate()), EXECUTION_FILLS, null));
            } else if (market.latestPrice() != null) {
                results.add(new ScopedOutcome(EXECUTION, outcomeCalculator.evaluateExecution(
                        ledgerFills(facts.fills()), ledger.positionQuantity(), market.latestPrice(),
                        market.evaluationDate()), market.sourceName(), market.marketTimestamp()));
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CLOSED"), null, null));
            } else {
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CURRENT"), null, null));
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CLOSED"), null, null));
            }
        }
        return results;
    }

    private List<LedgerFill> ledgerFills(List<FillFact> fills) {
        return fills.stream().map(fill -> new LedgerFill(
                TradeSide.valueOf(fill.side()), fill.executedAt(), fill.price(),
                fill.quantity(), fill.createdAt())).toList();
    }

    private boolean upsert(CaseFacts facts, List<ScopedOutcome> calculated) {
        TradeCaseEntity lockedCase = caseRepository.findByIdForUpdate(facts.caseId())
                .orElseThrow(() -> new TradeFeedbackNotFoundException("复盘单不存在"));
        FactsVersion currentVersion = new FactsVersion(lockedCase.getUpdatedAt(), fillFacts(facts.caseId()));
        if (!facts.version().equals(currentVersion)) {
            return false;
        }
        Instant calculatedAt = clock.instant();
        List<TradeOutcomeEntity> writes = new ArrayList<>();
        for (ScopedOutcome scoped : calculated) {
            OutcomeResult result = scoped.result();
            Optional<TradeOutcomeEntity> existing = outcomeRepository
                    .findByCaseIdAndBaselineTypeAndHorizon(facts.caseId(), scoped.baselineType(), result.horizon());
            if (existing.isPresent()) {
                if (isProtectedFixedRecommendation(scoped, existing.get())
                        || isOlderCurrent(scoped, existing.get())) {
                    continue;
                }
                existing.get().replaceWith(result, scoped.sourceName(), scoped.marketTimestamp(), calculatedAt);
                writes.add(existing.get());
                continue;
            }
            writes.add(entity(facts.caseId(), scoped, calculatedAt));
        }
        if (!writes.isEmpty()) {
            outcomeRepository.saveAll(writes);
        }
        Instant nextVersion = clock.instant();
        if (!nextVersion.isAfter(lockedCase.getUpdatedAt())) {
            nextVersion = lockedCase.getUpdatedAt().plusMillis(1);
        }
        lockedCase.touch(nextVersion);
        caseRepository.save(lockedCase);
        return true;
    }

    private boolean isProtectedFixedRecommendation(ScopedOutcome scoped, TradeOutcomeEntity existing) {
        return RECOMMENDATION.equals(scoped.baselineType())
                && List.of("T1", "T5", "T20").contains(scoped.result().horizon())
                && "MATURED".equals(existing.getStatus())
                && "PENDING".equals(scoped.result().status());
    }

    private boolean isOlderCurrent(ScopedOutcome scoped, TradeOutcomeEntity existing) {
        if (!"CURRENT".equals(scoped.result().horizon()) || !"MATURED".equals(existing.getStatus())) {
            return false;
        }
        if (EXECUTION.equals(scoped.baselineType()) && "PENDING".equals(scoped.result().status())) {
            return false;
        }
        LocalDate incomingDate = scoped.result().evaluationDate();
        LocalDate existingDate = existing.getEvaluationDate();
        if (incomingDate == null) {
            return existingDate != null;
        }
        if (existingDate == null) {
            return false;
        }
        int dateOrder = incomingDate.compareTo(existingDate);
        if (dateOrder != 0) {
            return dateOrder < 0;
        }
        if (existing.getMarketTimestamp() == null) {
            return false;
        }
        return scoped.marketTimestamp() == null
                || scoped.marketTimestamp().isBefore(existing.getMarketTimestamp());
    }

    private TradeOutcomeEntity entity(
            String caseId,
            ScopedOutcome scoped,
            Instant calculatedAt
    ) {
        OutcomeResult result = scoped.result();
        String snapshotId = UUID.randomUUID().toString();
        if ("PENDING".equals(result.status())) {
            return TradeOutcomeEntity.pending(
                    snapshotId, caseId, scoped.baselineType(), result.horizon(),
                    scoped.sourceName(), scoped.marketTimestamp(), calculatedAt);
        }
        return TradeOutcomeEntity.matured(
                snapshotId, caseId, scoped.baselineType(), result.horizon(), result.baselinePrice(),
                result.evaluationPrice(), result.evaluationDate(), result.returnPct(),
                result.maxRunupPct(), result.maxDrawdownPct(), scoped.sourceName(),
                scoped.marketTimestamp(), calculatedAt);
    }

    private List<FillFact> fillFacts(String caseId) {
        return fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(caseId).stream()
                .map(fill -> new FillFact(
                        fill.getFillId(), fill.getSide(), fill.getExecutedAt(), fill.getPrice(), fill.getQuantity(),
                        fill.getCreatedAt(), fill.getUpdatedAt()))
                .sorted(Comparator.comparing(FillFact::executedAt)
                        .thenComparing(FillFact::createdAt)
                        .thenComparing(FillFact::fillId))
                .toList();
    }

    private TradeCaseEntity requireCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("复盘单 ID 不能为空");
        }
        return caseRepository.findById(caseId.trim())
                .orElseThrow(() -> new TradeFeedbackNotFoundException("复盘单不存在"));
    }

    private TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null || cursor.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : cursor.getMessage();
    }

    private record CaseFacts(
            String caseId,
            String symbol,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            List<FillFact> fills,
            boolean hadExecutionOutcomes,
            FactsVersion version
    ) {
    }

    private record FillFact(
            String fillId,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record FactsVersion(Instant caseUpdatedAt, List<FillFact> orderedFills) {
        private FactsVersion {
            orderedFills = List.copyOf(orderedFills);
        }
    }

    private record MarketFacts(
            List<MarketBar> rows,
            BigDecimal latestPrice,
            LocalDate evaluationDate,
            String sourceName,
            Instant marketTimestamp,
            List<String> warnings
    ) {
    }

    private record ScopedOutcome(
            String baselineType,
            OutcomeResult result,
            String sourceName,
            Instant marketTimestamp
    ) {
    }
}
