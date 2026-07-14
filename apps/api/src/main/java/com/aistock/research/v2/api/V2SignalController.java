package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.aistock.research.v2.decision.V2RecommendationLedgerService;
import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import com.aistock.research.v2.strategy.StrategySignalFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/signals")
public class V2SignalController {

    private final V2RecommendationLedgerService ledgerService;

    public V2SignalController(V2RecommendationLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/sample")
    public V2SignalResponse sample(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "") String companyName,
            @RequestParam(defaultValue = "VALUE_REVERSION") StrategyCode strategyCode
    ) {
        Instant now = Instant.now();
        StrategySignal signal = StrategySignalFactory.research(
                strategyCode,
                versionOf(strategyCode),
                symbol,
                companyName.isBlank() ? symbol : companyName,
                now,
                now,
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("50.00"),
                new BigDecimal("40.00"),
                null,
                null,
                Map.of("source", "v2-compatibility-probe"));
        V2RecommendationLedgerEntity ledger = ledgerService.record(signal);
        return V2SignalResponse.from(signal, ledger);
    }

    private String versionOf(StrategyCode strategyCode) {
        return switch (strategyCode) {
            case VALUE_REVERSION -> "value-reversion-v2.0.0";
            case QUALITY_COMPOUNDER -> "quality-compounder-v2.0.0";
            case CYCLE_REVERSAL -> "cycle-reversal-v2.0.0";
            case SHORT_RIGHT_SIDE -> "short-right-side-v2.0.0";
            case HOT_DIRECTION -> "hot-direction-v2.0.0";
            case ALL_MARKET -> "all-market-v2.0.0";
        };
    }
}
