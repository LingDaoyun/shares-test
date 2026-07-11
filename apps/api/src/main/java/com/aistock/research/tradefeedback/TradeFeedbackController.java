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

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/trade-cases")
public class TradeFeedbackController {

    private final TradeFeedbackService tradeFeedbackService;
    private final TradeFeedbackMapper mapper;

    public TradeFeedbackController(TradeFeedbackService tradeFeedbackService, TradeFeedbackMapper mapper) {
        this.tradeFeedbackService = tradeFeedbackService;
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
            @RequestParam(required = false) String symbol
    ) {
        return translate(tradeFeedbackService::listCases).stream()
                .filter(tradeCase -> matches(tradeCase.getStatus(), status))
                .filter(tradeCase -> matches(tradeCase.getSymbol(), symbol))
                .map(tradeCase -> mapper.summary(
                        tradeCase,
                        translate(() -> tradeFeedbackService.ledger(tradeCase.getCaseId(), null))
                ))
                .toList();
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

    private TradeCaseDetail detail(TradeCaseEntity tradeCase) {
        List<TradeFillEntity> fills = translate(() -> tradeFeedbackService.fills(tradeCase.getCaseId()));
        return mapper.detail(
                tradeCase,
                translate(() -> tradeFeedbackService.ledger(tradeCase.getCaseId(), null)),
                fills
        );
    }

    private boolean matches(String actual, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return actual.toLowerCase(Locale.ROOT).equals(filter.trim().toLowerCase(Locale.ROOT));
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
