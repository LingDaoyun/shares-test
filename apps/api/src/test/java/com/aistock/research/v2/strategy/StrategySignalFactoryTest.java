package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategySignalFactoryTest {

    @Test
    void convenienceConstructorsDefaultToRuleEngineProvenance() {
        StrategySignal signal = new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.WATCH, StrategyAction.NEXT_WATCH, null, "", "",
                new BigDecimal("61.00"), new BigDecimal("82.00"), null, null,
                List.of(), List.of(), Map.of("source", "constructor"));

        assertThat(signal.signalProvenance()).isEqualTo(SignalProvenance.RULE_ENGINE);
    }

    @Test
    void createsBlockedSignalWithSeparatedScoresAndReasons() {
        StrategySignal signal = StrategySignalFactory.blocked(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                "002714",
                "牧原股份",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                StrategyAction.DATA_BLOCKED,
                List.of("QUOTE_SNAPSHOT_MISSING"),
                Map.of("quoteStage", "AFTER_HOURS_1520"));

        assertThat(signal.strategyCode()).isEqualTo(StrategyCode.SHORT_RIGHT_SIDE);
        assertThat(signal.action()).isEqualTo(StrategyAction.DATA_BLOCKED);
        assertThat(signal.candidateStage()).isEqualTo(CandidateStage.BLOCKED);
        assertThat(signal.sourceQuality()).isEqualTo(SourceQualityStatus.MISSING);
        assertThat(signal.rankScore()).isNull();
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.riskReward()).isNull();
        assertThat(signal.blockedReasons()).containsExactly("QUOTE_SNAPSHOT_MISSING");
        assertThat(signal.context()).containsEntry("quoteStage", "AFTER_HOURS_1520");
        assertThat(signal.replayPayload()).containsEntry("quoteStage", "AFTER_HOURS_1520");
    }

    @Test
    void createsResearchSignalWithoutPretendingScoreIsProbability() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of("valuationContext", "industry-percentile"));

        assertThat(signal.rankScore()).isEqualByComparingTo(new BigDecimal("72.35"));
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("84.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.action()).isEqualTo(StrategyAction.NEXT_WATCH);
        assertThat(signal.blockedReasons()).isEmpty();
        assertThat(signal.signalProvenance()).isEqualTo(SignalProvenance.RULE_ENGINE);
        assertThat(signal.sourceQuality()).isEqualTo(SourceQualityStatus.SINGLE_SOURCE);
        assertThat(signal.replayPayload())
                .containsEntry("strategyVersion", "value-reversion-v2.0.0")
                .containsEntry("action", "NEXT_WATCH")
                .containsEntry("dataConfidence", new BigDecimal("84.00"))
                .containsEntry("context", Map.of("valuationContext", "industry-percentile"));
    }

    @Test
    void createsCompatibilityProbeOnlyWhenProvenanceIsExplicitlyRequested() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"), new BigDecimal("84.00"), null, null, Map.of(), Map.of(),
                SourceQualityStatus.SINGLE_SOURCE, SignalProvenance.COMPATIBILITY_PROBE);

        assertThat(signal.signalProvenance()).isEqualTo(SignalProvenance.COMPATIBILITY_PROBE);
        assertThat(signal.replayPayload()).containsEntry("signalProvenance", "COMPATIBILITY_PROBE");
    }

    @Test
    void compatibilityConstructorDerivesReplayPayloadFromCoreDecisionFields() {
        StrategySignal signal = new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                null,
                "",
                "",
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                List.of(),
                List.of(),
                Map.of());

        assertThat(signal.replayPayload()).isNotEmpty()
                .containsEntry("strategyVersion", "value-reversion-v2.0.0")
                .containsEntry("symbol", "600036");
        assertThat(signal.sourceQuality()).isEqualTo(SourceQualityStatus.SINGLE_SOURCE);
    }

    @Test
    void canonicalStrategyVersionOverridesCallerReplayPayload() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of("valuationContext", "industry-percentile"),
                Map.of("strategyVersion", "caller-version"));

        assertThat(signal.replayPayload()).containsEntry("strategyVersion", "value-reversion-v2.0.0");
    }

    @Test
    void directCompatibilityConstructorDefaultsToSingleSource() {
        StrategySignal signal = new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                null,
                "",
                "",
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                List.of(),
                List.of(),
                Map.of());

        assertThat(signal.sourceQuality()).isEqualTo(SourceQualityStatus.SINGLE_SOURCE);
    }

    @Test
    void rejectsDataCutoffAfterDecision() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:19:30Z"),
                Instant.parse("2026-07-14T07:20:00Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataCutoffAt");
    }

    @Test
    void rejectsNonFiniteJsonNumbers() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                Map.of("nan", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayPayload");

        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                Map.of("infinity", Float.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayPayload");
    }

    @Test
    void rejectsMutableNumericReplayPayloadValues() {
        AtomicInteger mutableNumber = new AtomicInteger(7);

        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.now(), Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"), new BigDecimal("84.00"), null, null, Map.of(),
                Map.of("mutable", mutableNumber)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported numeric type");
    }

    @Test
    void rejectsBigDecimalSubclassReplayPayloadValues() {
        BigDecimal subclassValue = new BigDecimal("12.34") {
        };

        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.now(), Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"), new BigDecimal("84.00"), null, null, Map.of(),
                Map.of("subclass", subclassValue)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported numeric type");
    }

    @Test
    void normalizesDecisionMetricsAtSignalConstructionWithDeterministicRounding() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("72.345"), new BigDecimal("84.005"), new BigDecimal("63.995"),
                new BigDecimal("2.345"), Map.of(), Map.of(), SourceQualityStatus.SINGLE_SOURCE);

        assertThat(signal.rankScore()).isEqualByComparingTo("72.35");
        assertThat(signal.dataConfidence()).isEqualByComparingTo("84.01");
        assertThat(signal.historicalHitRate()).isEqualByComparingTo("64.00");
        assertThat(signal.riskReward()).isEqualByComparingTo("2.35");
    }

    @Test
    void rejectsDecisionMetricsOutsideTheirSignalBoundaryRanges() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.now(), Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("100.01"), new BigDecimal("84.00"), null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankScore must be between 0 and 100");
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.now(), Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH,
                new BigDecimal("1.00001"), "", "", new BigDecimal("72.35"), new BigDecimal("84.00"),
                null, null, List.of(), List.of(), Map.of(), SourceQualityStatus.SINGLE_SOURCE, Map.of(),
                SignalProvenance.RULE_ENGINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positionLimit must be between 0 and 1");
    }

    @Test
    void copiesReplayPayloadAndKeepsItImmutable() {
        Map<String, Object> replayPayload = new HashMap<>();
        replayPayload.put("valuationContext", "industry-percentile");

        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                replayPayload);

        replayPayload.put("valuationContext", "changed");

        assertThat(signal.replayPayload()).containsEntry("valuationContext", "industry-percentile");
        assertThatThrownBy(() -> signal.replayPayload().put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deeplyCopiesNestedReplayPayloadCollections() {
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put("label", "original");
        List<Object> nestedList = new ArrayList<>();
        nestedList.add(nestedMap);
        Map<String, Object> replayPayload = new HashMap<>();
        replayPayload.put("items", nestedList);

        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                replayPayload);

        nestedMap.put("label", "changed");
        nestedList.add("changed");

        Map<String, Object> frozenNestedMap = (Map<String, Object>) ((List<?>) signal.replayPayload().get("items")).get(0);
        assertThat(frozenNestedMap).containsEntry("label", "original");
        assertThat(signal.replayPayload().get("items")).asList().hasSize(1);
        assertThatThrownBy(() -> frozenNestedMap.put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) signal.replayPayload().get("items")).add("newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsupportedReplayPayloadValues() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                Map.of("unsupported", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayPayload");
    }

    @Test
    void rejectsResearchSignalWithoutDataConfidence() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                null,
                null,
                null,
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataConfidence");
    }

    @Test
    void rejectsMissingSourceQualityForResearchSignal() {
        assertThatThrownBy(() -> StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of(),
                Map.of(),
                SourceQualityStatus.MISSING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATA_BLOCKED");
    }

    @Test
    void rejectsAiEvidenceOnlyAddSignal() {
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                CandidateStage.QUALIFIED,
                StrategyAction.ADD,
                null,
                "",
                "",
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                SourceQualityStatus.VERIFIED,
                Map.of(),
                SignalProvenance.AI_EVIDENCE_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_EVIDENCE_ONLY");
    }

    @Test
    void rejectsMissingMandatoryDecisionMetadata() {
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                " ",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                null,
                "",
                "",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategyVersion");
    }

    @Test
    void rejectsEveryOtherMissingMandatoryDecisionField() {
        assertThatThrownBy(() -> validSignal(null, "600036", "招商银行", Instant.now(),
                Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("strategyCode");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, " ", "招商银行", Instant.now(),
                Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("symbol");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", " ", Instant.now(),
                Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("companyName");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", "招商银行", null,
                Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("decisionAt");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", "招商银行", Instant.now(),
                null, CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("dataCutoffAt");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", "招商银行", Instant.now(),
                Instant.now(), null, StrategyAction.NEXT_WATCH, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("candidateStage");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", "招商银行", Instant.now(),
                Instant.now(), CandidateStage.RESEARCH, null, SourceQualityStatus.VERIFIED))
                .hasMessageContaining("action");
        assertThatThrownBy(() -> validSignal(StrategyCode.VALUE_REVERSION, "600036", "招商银行", Instant.now(),
                Instant.now(), CandidateStage.RESEARCH, StrategyAction.NEXT_WATCH, null))
                .hasMessageContaining("sourceQuality");
    }

    @Test
    void rejectsBlockedStageWithoutBlockedActionOrReason() {
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.BLOCKED,
                StrategyAction.NEXT_WATCH,
                null,
                "",
                "",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLOCKED");
    }

    @Test
    void rejectsBlockedActionOutsideBlockedStage() {
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.DATA_BLOCKED,
                null,
                "",
                "",
                null,
                null,
                null,
                null,
                List.of(),
                List.of("QUOTE_SNAPSHOT_MISSING"),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATA_BLOCKED");
    }

    @Test
    void rejectsBlockedStageWithoutReason() {
        assertThatThrownBy(() -> new StrategySignal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.BLOCKED,
                StrategyAction.DATA_BLOCKED,
                null,
                "",
                "",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockedReasons");
    }

    @Test
    void rejectsBlankBlockedReason() {
        assertThatThrownBy(() -> StrategySignalFactory.blocked(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.now(),
                Instant.now(),
                StrategyAction.DATA_BLOCKED,
                List.of("  "),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockedReasons");
    }

    private static StrategySignal validSignal(
            StrategyCode strategyCode,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            SourceQualityStatus sourceQuality
    ) {
        return new StrategySignal(strategyCode, "value-reversion-v2.0.0", symbol, companyName,
                decisionAt, dataCutoffAt, candidateStage, action, null, "", "", null, null,
                null, null, List.of(), List.of(), Map.of(), sourceQuality);
    }
}
