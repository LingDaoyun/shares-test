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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradeOutcomeService {

    private static final Logger logger = LoggerFactory.getLogger(TradeOutcomeService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String RECOMMENDATION = "RECOMMENDATION";
    private static final String EXECUTION = "EXECUTION";

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
        writeTransaction.executeWithoutResult(status -> upsert(facts.caseId(), calculated));
        return new TradeOutcomeRefresh(market.warnings());
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
        List<TradeFillEntity> fills = fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(caseId);
        return new CaseFacts(
                tradeCase.getCaseId(),
                tradeCase.getSymbol(),
                tradeCase.getRecommendedPrice(),
                tradeCase.getRecommendedAt(),
                List.copyOf(fills));
    }

    private MarketFacts loadMarketFacts(CaseFacts facts) {
        LocalDate recommendationDate = facts.recommendedAt().atZone(SHANGHAI).toLocalDate();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        List<MarketBar> rows = gateway.dailyKLines(facts.symbol(), recommendationDate, today);
        Optional<LatestMarketPrice> latest = gateway.latestPrice(facts.symbol());
        List<String> warnings = new ArrayList<>();
        if (latest.isPresent()) {
            if (EastMoneyTradeMarketDataGateway.TENCENT_LIVE_QUOTE_FALLBACK.equals(latest.get().source())) {
                warnings.add("CURRENT 使用 " + latest.get().source());
            }
            return new MarketFacts(List.copyOf(rows), latest.get().price(), today, warnings);
        }

        Optional<MarketBar> lastBar = rows.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null
                        && row.close().compareTo(BigDecimal.ZERO) > 0)
                .max(java.util.Comparator.comparing(MarketBar::tradeDate));
        if (lastBar.isPresent()) {
            warnings.add("CURRENT 使用 LAST_KLINE_CLOSE_FALLBACK");
            return new MarketFacts(List.copyOf(rows), lastBar.get().close(), lastBar.get().tradeDate(), warnings);
        }
        return new MarketFacts(List.copyOf(rows), null, null, warnings);
    }

    private List<ScopedOutcome> calculate(CaseFacts facts, MarketFacts market) {
        List<ScopedOutcome> results = new ArrayList<>();
        outcomeCalculator.evaluateRecommendation(
                        facts.recommendedPrice(), market.rows(), facts.recommendedAt())
                .forEach(result -> results.add(new ScopedOutcome(RECOMMENDATION, result)));
        results.add(new ScopedOutcome(RECOMMENDATION, outcomeCalculator.evaluateRecommendationCurrent(
                facts.recommendedPrice(), market.rows(), facts.recommendedAt(),
                market.latestPrice(), market.evaluationDate())));

        if (!facts.fills().isEmpty()) {
            TradeLedgerSummary ledger = ledgerCalculator.calculate(facts.fills(), market.latestPrice());
            if (ledger.positionQuantity() == 0) {
                results.add(new ScopedOutcome(EXECUTION, outcomeCalculator.evaluateExecution(
                        ledgerFills(facts.fills()), 0, null, market.evaluationDate())));
            } else if (market.latestPrice() != null) {
                results.add(new ScopedOutcome(EXECUTION, outcomeCalculator.evaluateExecution(
                        ledgerFills(facts.fills()), ledger.positionQuantity(), market.latestPrice(),
                        market.evaluationDate())));
            } else {
                results.add(new ScopedOutcome(EXECUTION, OutcomeResult.pending("CURRENT")));
            }
        }
        return results;
    }

    private List<LedgerFill> ledgerFills(List<TradeFillEntity> fills) {
        return fills.stream().map(fill -> new LedgerFill(
                TradeSide.valueOf(fill.getSide()), fill.getExecutedAt(), fill.getPrice(),
                fill.getQuantity(), fill.getCreatedAt())).toList();
    }

    private void upsert(String caseId, List<ScopedOutcome> calculated) {
        caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new TradeFeedbackNotFoundException("复盘单不存在"));
        Instant calculatedAt = clock.instant();
        List<TradeOutcomeEntity> writes = new ArrayList<>();
        for (ScopedOutcome scoped : calculated) {
            OutcomeResult result = scoped.result();
            Optional<TradeOutcomeEntity> existing = outcomeRepository
                    .findByCaseIdAndBaselineTypeAndHorizon(caseId, scoped.baselineType(), result.horizon());
            if (existing.isPresent()) {
                if ("MATURED".equals(existing.get().getStatus()) && "PENDING".equals(result.status())) {
                    continue;
                }
                existing.get().replaceWith(result, calculatedAt);
                writes.add(existing.get());
                continue;
            }
            writes.add(entity(caseId, scoped.baselineType(), result, calculatedAt));
        }
        if (!writes.isEmpty()) {
            outcomeRepository.saveAll(writes);
        }
    }

    private TradeOutcomeEntity entity(
            String caseId,
            String baselineType,
            OutcomeResult result,
            Instant calculatedAt
    ) {
        String snapshotId = UUID.randomUUID().toString();
        if ("PENDING".equals(result.status())) {
            return TradeOutcomeEntity.pending(snapshotId, caseId, baselineType, result.horizon(), calculatedAt);
        }
        return TradeOutcomeEntity.matured(
                snapshotId, caseId, baselineType, result.horizon(), result.baselinePrice(),
                result.evaluationPrice(), result.evaluationDate(), result.returnPct(),
                result.maxRunupPct(), result.maxDrawdownPct(), calculatedAt);
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
            List<TradeFillEntity> fills
    ) {
    }

    private record MarketFacts(
            List<MarketBar> rows,
            BigDecimal latestPrice,
            LocalDate evaluationDate,
            List<String> warnings
    ) {
    }

    private record ScopedOutcome(String baselineType, OutcomeResult result) {
    }
}
