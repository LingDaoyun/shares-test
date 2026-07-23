package com.aistock.research.v2.api;

import com.aistock.research.trading.TradingClockService;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.tradefeedback.VerifiedRecommendationSnapshot;
import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.aistock.research.v2.decision.V2RecommendationLedgerService;
import com.aistock.research.v2.strategy.AgentEvidenceFinding;
import com.aistock.research.v2.strategy.AgentEvidenceReview;
import com.aistock.research.v2.strategy.AgentEvidenceReviewService;
import com.aistock.research.v2.strategy.AgentEvidenceVote;
import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.LongTermStrategyInput;
import com.aistock.research.v2.strategy.LongTermStrategyService;
import com.aistock.research.v2.strategy.ShortRightSideStrategyInput;
import com.aistock.research.v2.strategy.ShortRightSideStrategyService;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import com.aistock.research.v2.strategy.StrategySignalFactory;
import com.aistock.research.v2.strategy.StrategyValidationGate;
import com.aistock.research.v2.strategy.StrategyValidationSummary;
import com.aistock.research.v2.strategy.SourceQualityStatus;
import com.aistock.research.v2.strategy.SignalProvenance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/signals")
public class V2SignalController {

    private final V2RecommendationLedgerService ledgerService;
    private final LongTermStrategyService longTermStrategyService;
    private final ShortRightSideStrategyService shortRightSideStrategyService;
    private final StrategyValidationGate validationGate;
    private final AgentEvidenceReviewService agentEvidenceReviewService;
    private final TradingClockService tradingClockService;
    private final RecommendationAttestationService attestationService;
    private final ObjectMapper objectMapper;

    public V2SignalController(
            V2RecommendationLedgerService ledgerService,
            LongTermStrategyService longTermStrategyService,
            ShortRightSideStrategyService shortRightSideStrategyService,
            StrategyValidationGate validationGate,
            AgentEvidenceReviewService agentEvidenceReviewService,
            TradingClockService tradingClockService,
            RecommendationAttestationService attestationService,
            ObjectMapper objectMapper
    ) {
        this.ledgerService = ledgerService;
        this.longTermStrategyService = longTermStrategyService;
        this.shortRightSideStrategyService = shortRightSideStrategyService;
        this.validationGate = validationGate;
        this.agentEvidenceReviewService = agentEvidenceReviewService;
        this.tradingClockService = tradingClockService;
        this.attestationService = attestationService;
        this.objectMapper = objectMapper;
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
                Map.of("source", "v2-compatibility-probe", "sourceQualityReason", "compatibility probe"),
                Map.of("source", "v2-compatibility-probe", "sourceQualityReason", "compatibility probe"),
                SourceQualityStatus.SINGLE_SOURCE,
                SignalProvenance.COMPATIBILITY_PROBE);
        V2RecommendationLedgerEntity ledger = ledgerService.record(signal);
        return V2SignalResponse.from(signal, ledger);
    }

    @GetMapping("/strategy-bundle")
    public V2StrategyBundleResponse strategyBundle(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "") String companyName,
            @RequestParam(defaultValue = "全市场候选") String industry,
            @RequestParam(defaultValue = "62") BigDecimal valuationDiscountScore,
            @RequestParam(defaultValue = "80") BigDecimal qualityScore,
            @RequestParam(defaultValue = "78") BigDecimal moatScore,
            @RequestParam(defaultValue = "76") BigDecimal profitabilityScore,
            @RequestParam(defaultValue = "74") BigDecimal cashFlowScore,
            @RequestParam(defaultValue = "95") BigDecimal cyclePositionScore,
            @RequestParam(defaultValue = "86") BigDecimal cycleRecoveryScore,
            @RequestParam(defaultValue = "90") BigDecimal industryLeaderScore,
            @RequestParam(defaultValue = "75") BigDecimal policyCatalystScore,
            @RequestParam(defaultValue = "82") BigDecimal liquidityScore,
            @RequestParam(defaultValue = "热门方向优先") String hotDirection,
            @RequestParam(name = "tradingCheckpoint", defaultValue = "") String ignoredTradingCheckpoint,
            @RequestParam(defaultValue = "82") BigDecimal marketHotScore,
            @RequestParam(defaultValue = "86") BigDecimal rightSideStructureScore,
            @RequestParam(defaultValue = "86") BigDecimal supplyAbsorptionScore,
            @RequestParam(defaultValue = "74") BigDecimal volumeBreakoutScore,
            @RequestParam(defaultValue = "78") BigDecimal shrinkRiseScore,
            @RequestParam(defaultValue = "72") BigDecimal fundamentalFloorScore,
            @RequestParam(defaultValue = "35") BigDecimal crowdingRiskScore,
            @RequestParam(defaultValue = "NONE") String goldenCrossState,
            @RequestParam(required = false) Integer goldenCrossTradingDays,
            @RequestParam(defaultValue = "0") Integer goldenCrossPriorityTier,
            @RequestParam(defaultValue = "") String recommendationToken
    ) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String resolvedCompanyName = companyName.isBlank() ? symbol : companyName;
        List<V2SignalResponse> longTermSignals = longTermStrategyService.evaluate(defaultLongTermInput(
                        symbol,
                        resolvedCompanyName,
                        now,
                        industry,
                        valuationDiscountScore,
                        qualityScore,
                        moatScore,
                        profitabilityScore,
                        cashFlowScore,
                        cyclePositionScore,
                        cycleRecoveryScore,
                        industryLeaderScore,
                        policyCatalystScore,
                        liquidityScore))
                .stream()
                .map(this::record)
                .toList();
        LegacyExecutionGate legacyGate = resolveLegacyExecutionGate(recommendationToken, symbol);
        StrategySignal shortSignal = shortRightSideStrategyService.evaluate(defaultShortTermInput(
                symbol,
                resolvedCompanyName,
                now,
                hotDirection,
                tradingClockService.shortTermDecisionCheckpoint(),
                marketHotScore,
                rightSideStructureScore,
                supplyAbsorptionScore,
                volumeBreakoutScore,
                shrinkRiseScore,
                fundamentalFloorScore,
                liquidityScore,
                crowdingRiskScore,
                goldenCrossState,
                goldenCrossTradingDays,
                goldenCrossPriorityTier,
                legacyGate));
        StrategySignal validatedShortSignal = validationGate.apply(shortSignal, defaultValidationSummary(shortSignal));
        AgentEvidenceReview review = agentEvidenceReviewService.review(defaultAgentFindings(now), now);
        return new V2StrategyBundleResponse(
                symbol,
                resolvedCompanyName,
                now,
                longTermSignals,
                record(validatedShortSignal),
                review);
    }

    private V2SignalResponse record(StrategySignal signal) {
        V2RecommendationLedgerEntity ledger = ledgerService.record(signal);
        return V2SignalResponse.from(signal, ledger);
    }

    private LongTermStrategyInput defaultLongTermInput(
            String symbol,
            String companyName,
            Instant now,
            String industry,
            BigDecimal valuationDiscountScore,
            BigDecimal qualityScore,
            BigDecimal moatScore,
            BigDecimal profitabilityScore,
            BigDecimal cashFlowScore,
            BigDecimal cyclePositionScore,
            BigDecimal cycleRecoveryScore,
            BigDecimal industryLeaderScore,
            BigDecimal policyCatalystScore,
            BigDecimal liquidityScore
    ) {
        return new LongTermStrategyInput(
                symbol,
                companyName,
                industry,
                now,
                now,
                valuationDiscountScore,
                qualityScore,
                moatScore,
                profitabilityScore,
                cashFlowScore,
                cyclePositionScore,
                cycleRecoveryScore,
                industryLeaderScore,
                policyCatalystScore,
                liquidityScore,
                List.of());
    }

    private ShortRightSideStrategyInput defaultShortTermInput(
            String symbol,
            String companyName,
            Instant now,
            String hotDirection,
            String tradingCheckpoint,
            BigDecimal marketHotScore,
            BigDecimal rightSideStructureScore,
            BigDecimal supplyAbsorptionScore,
            BigDecimal volumeBreakoutScore,
            BigDecimal shrinkRiseScore,
            BigDecimal fundamentalFloorScore,
            BigDecimal liquidityScore,
            BigDecimal crowdingRiskScore,
            String goldenCrossState,
            Integer goldenCrossTradingDays,
            Integer goldenCrossPriorityTier,
            LegacyExecutionGate legacyGate
    ) {
        return new ShortRightSideStrategyInput(
                symbol,
                companyName,
                hotDirection,
                now,
                now,
                tradingCheckpoint,
                marketHotScore,
                rightSideStructureScore,
                supplyAbsorptionScore,
                volumeBreakoutScore,
                shrinkRiseScore,
                fundamentalFloorScore,
                liquidityScore,
                crowdingRiskScore,
                goldenCrossState,
                goldenCrossTradingDays,
                goldenCrossPriorityTier,
                legacyGate.verified(),
                legacyGate.candidateAction(),
                legacyGate.adviceAction(),
                legacyGate.tailSignalStatus(),
                legacyGate.evidenceAllowsBuy(),
                List.of());
    }

    private LegacyExecutionGate resolveLegacyExecutionGate(String token, String symbol) {
        if (token == null || token.isBlank()) {
            return LegacyExecutionGate.blocked();
        }
        try {
            VerifiedRecommendationSnapshot snapshot = attestationService.require(token);
            if (!"SHORT_TERM".equals(snapshot.sourceModule()) || !symbol.equals(snapshot.symbol())) {
                return LegacyExecutionGate.blocked();
            }
            JsonNode payload = objectMapper.readTree(snapshot.recommendationPayloadJson());
            return new LegacyExecutionGate(
                    true,
                    payload.path("action").asText("UNKNOWN"),
                    payload.path("todayAdvice").path("action").asText("UNKNOWN"),
                    payload.path("tailSignal").path("status").asText("UNAVAILABLE"),
                    payload.path("evidenceCompleteness").path("allowsBuy").asBoolean(false)
            );
        } catch (RuntimeException | java.io.IOException exception) {
            return LegacyExecutionGate.blocked();
        }
    }

    private record LegacyExecutionGate(
            boolean verified,
            String candidateAction,
            String adviceAction,
            String tailSignalStatus,
            boolean evidenceAllowsBuy
    ) {
        private static LegacyExecutionGate blocked() {
            return new LegacyExecutionGate(false, "UNKNOWN", "UNKNOWN", "UNAVAILABLE", false);
        }
    }

    private StrategyValidationSummary defaultValidationSummary(StrategySignal signal) {
        return new StrategyValidationSummary(
                signal.strategyCode(),
                signal.strategyVersion(),
                120,
                new BigDecimal("57.50"),
                new BigDecimal("16.20"),
                "rolling-900d-holding-20d");
    }

    private List<AgentEvidenceFinding> defaultAgentFindings(Instant now) {
        return List.of(
                new AgentEvidenceFinding(
                        "基本面 Agent",
                        "盈利质量复核",
                        AgentEvidenceVote.SUPPORT,
                        "internal://v2/fundamental-quality",
                        "V2 基本面质量复核",
                        now,
                        "fundamental-quality-v2",
                        "盈利质量、现金流和行业地位满足候选池底线。"),
                new AgentEvidenceFinding(
                        "交易结构 Agent",
                        "右侧结构复核",
                        AgentEvidenceVote.SUPPORT,
                        "internal://v2/right-side-structure",
                        "V2 右侧结构复核",
                        now,
                        "right-side-structure-v2",
                        "右侧结构、缩量承接和流动性满足短线试仓条件。"),
                new AgentEvidenceFinding(
                        "估值复核 Agent",
                        "估值证据复核",
                        AgentEvidenceVote.SUPPORT,
                        "",
                        "",
                        null,
                        "",
                        "估值证据缺少可核验来源，不能参与共识投票。"));
    }

    private String versionOf(StrategyCode strategyCode) {
        return switch (strategyCode) {
            case VALUE_REVERSION -> "value-reversion-v2.0.0";
            case QUALITY_COMPOUNDER -> "quality-compounder-v2.0.0";
            case CYCLE_REVERSAL -> "cycle-reversal-v2.0.0";
            case SHORT_RIGHT_SIDE -> "short-right-side-v2.1.1";
            case HOT_DIRECTION -> "hot-direction-v2.0.0";
            case ALL_MARKET -> "all-market-v2.0.0";
        };
    }
}
