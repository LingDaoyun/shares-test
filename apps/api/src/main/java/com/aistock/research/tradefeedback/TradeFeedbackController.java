package com.aistock.research.tradefeedback;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/trade-cases")
public class TradeFeedbackController {

    private final TradeFeedbackService tradeFeedbackService;
    private final TradeOutcomeService tradeOutcomeService;
    private final TradeFeedbackMapper mapper;

    public TradeFeedbackController(
            TradeFeedbackService tradeFeedbackService,
            TradeOutcomeService tradeOutcomeService,
            TradeFeedbackMapper mapper
    ) {
        this.tradeFeedbackService = tradeFeedbackService;
        this.tradeOutcomeService = tradeOutcomeService;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeCaseDetail create(@Valid @RequestBody CreateTradeCaseRequest request) {
        return detail(translate(() -> tradeFeedbackService.createCase(request)));
    }

    @GetMapping
    public List<TradeCaseSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Instant beforeCreatedAt,
            @RequestParam(required = false) String beforeCaseId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<TradeCaseEntity> tradeCases = translate(() -> tradeFeedbackService.listCases(
                status, symbol, beforeCreatedAt, beforeCaseId, limit));
        Map<String, List<TradeOutcomeEntity>> outcomesByCaseId = translate(() -> tradeOutcomeService.outcomes(
                tradeCases.stream().map(TradeCaseEntity::getCaseId).toList()
        )).stream().collect(java.util.stream.Collectors.groupingBy(TradeOutcomeEntity::getCaseId));
        Map<String, BigDecimal> latestPrices = new java.util.LinkedHashMap<>();
        for (TradeCaseEntity tradeCase : tradeCases) {
            BigDecimal latestPrice = currentEvaluationPrice(
                    visibleOutcomes(
                            tradeCase,
                            outcomesByCaseId.getOrDefault(tradeCase.getCaseId(), List.of())));
            if (latestPrice != null) {
                latestPrices.put(tradeCase.getCaseId(), latestPrice);
            }
        }
        Map<String, TradeLedgerSummary> ledgers = translate(() -> tradeFeedbackService.ledgers(
                tradeCases.stream().map(TradeCaseEntity::getCaseId).toList(), latestPrices));
        return tradeCases.stream().map(tradeCase -> {
            List<TradeOutcomeEntity> outcomes = visibleOutcomes(
                    tradeCase,
                    outcomesByCaseId.getOrDefault(tradeCase.getCaseId(), List.of()));
            return mapper.summary(
                    tradeCase,
                    ledgers.get(tradeCase.getCaseId()),
                    outcomes
            );
        }).toList();
    }

    @GetMapping("/{caseId}")
    public TradeCaseDetail detail(@PathVariable String caseId) {
        return detail(translate(() -> tradeFeedbackService.getCase(caseId)));
    }

    @PostMapping("/{caseId}/fills")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeCaseDetail addFill(
            @PathVariable String caseId,
            @Valid @RequestBody UpsertTradeFillRequest request
    ) {
        return detail(translate(() -> tradeFeedbackService.addFill(caseId, request)));
    }

    @PutMapping("/{caseId}/fills/{fillId}")
    public TradeCaseDetail updateFill(
            @PathVariable String caseId,
            @PathVariable String fillId,
            @Valid @RequestBody UpsertTradeFillRequest request
    ) {
        return detail(translate(() -> tradeFeedbackService.updateFill(caseId, fillId, request)));
    }

    @DeleteMapping("/{caseId}/fills/{fillId}")
    public TradeCaseDetail deleteFill(@PathVariable String caseId, @PathVariable String fillId) {
        return detail(translate(() -> tradeFeedbackService.deleteFill(caseId, fillId)));
    }

    @PostMapping("/{caseId}/cancel")
    public TradeCaseDetail cancel(@PathVariable String caseId) {
        return detail(translate(() -> tradeFeedbackService.cancelCase(caseId)));
    }

    @PostMapping("/{caseId}/refresh")
    public TradeCaseDetail refresh(@PathVariable String caseId) {
        TradeOutcomeRefresh refresh = translate(() -> tradeOutcomeService.refresh(caseId));
        TradeCaseEntity tradeCase = translate(() -> tradeFeedbackService.getCase(caseId));
        return detail(tradeCase, refresh.warnings());
    }

    private TradeCaseDetail detail(TradeCaseEntity tradeCase) {
        return detail(tradeCase, List.of());
    }

    private TradeCaseDetail detail(TradeCaseEntity tradeCase, List<String> warnings) {
        List<TradeFillSnapshot> fills = translate(() -> tradeFeedbackService.fills(tradeCase.getCaseId()));
        List<TradeOutcomeEntity> outcomes = visibleOutcomes(
                tradeCase,
                translate(() -> tradeOutcomeService.outcomes(tradeCase.getCaseId())));
        BigDecimal latestPrice = currentEvaluationPrice(outcomes);
        List<String> visibleWarnings = new java.util.ArrayList<>(warnings);
        if (tradeCase.isOutcomeDirty()) {
            visibleWarnings.add("成交事实已变更，执行结果等待重新计算");
        }
        return mapper.detail(
                tradeCase,
                translate(() -> tradeFeedbackService.ledger(tradeCase.getCaseId(), latestPrice)),
                fills,
                outcomes,
                List.copyOf(visibleWarnings)
        );
    }

    private BigDecimal currentEvaluationPrice(List<TradeOutcomeEntity> outcomes) {
        return outcomes.stream()
                .filter(outcome -> "CURRENT".equals(outcome.getHorizon()))
                .filter(outcome -> "MATURED".equals(outcome.getStatus()))
                .sorted(java.util.Comparator.comparing(
                        outcome -> "EXECUTION".equals(outcome.getBaselineType()) ? 0 : 1))
                .map(TradeOutcomeEntity::getEvaluationPrice)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<TradeOutcomeEntity> visibleOutcomes(
            TradeCaseEntity tradeCase,
            List<TradeOutcomeEntity> outcomes
    ) {
        if (!tradeCase.isOutcomeDirty()) {
            return outcomes;
        }
        return outcomes.stream()
                .filter(outcome -> !"EXECUTION".equals(outcome.getBaselineType()))
                .toList();
    }

    private <T> T translate(Supplier<T> action) {
        try {
            return action.get();
        } catch (TradeFeedbackNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (TradeFeedbackConflictException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }
}
