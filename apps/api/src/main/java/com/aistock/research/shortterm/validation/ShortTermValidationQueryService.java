package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.RecommendationSource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ShortTermValidationQueryService {

    private static final int MAX_COHORTS_PER_REQUEST = 64;

    private final ShortTermSignalOutcomeRepository outcomeRepository;
    private final ShortTermValidationSettings settings;
    private final ShortTermValidationSummaryCalculator calculator;

    public ShortTermValidationQueryService(
            ShortTermSignalOutcomeRepository outcomeRepository,
            ShortTermValidationSettings settings,
            ShortTermValidationSummaryCalculator calculator
    ) {
        this.outcomeRepository = outcomeRepository;
        this.settings = settings;
        this.calculator = calculator;
    }

    public List<ShortTermValidationSummary> summaries(ShortTermValidationBatchRequest request) {
        List<ShortTermValidationCohortRequest> source = request == null || request.cohorts() == null
                ? List.of() : request.cohorts();
        Map<CohortKey, CohortKey> unique = new LinkedHashMap<>();
        for (ShortTermValidationCohortRequest item : source) {
            if (item == null) {
                continue;
            }
            CohortKey key = normalize(item);
            unique.putIfAbsent(key, key);
            if (unique.size() > MAX_COHORTS_PER_REQUEST) {
                throw new IllegalArgumentException("单次最多查询 " + MAX_COHORTS_PER_REQUEST + " 个短线验证分组");
            }
        }
        int minimum = settings.minimumCohortSamples();
        return unique.keySet().stream()
                .map(key -> summarize(key, minimum))
                .toList();
    }

    private ShortTermValidationSummary summarize(CohortKey key, int minimum) {
        String ruleVersion = RecommendationSource.SHORT_TERM.ruleVersion();
        if (!settings.enabled()) {
            return new ShortTermValidationSummary(
                    ruleVersion,
                    key.signalFamily(),
                    key.marketRegime(),
                    key.horizon(),
                    "VALIDATION_DISABLED",
                    minimum,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        List<ShortTermValidationSample> samples = outcomeRepository.findMaturedCohortSamples(
                ruleVersion, key.signalFamily(), key.marketRegime(), key.horizon());
        return calculator.summarize(
                ruleVersion,
                key.signalFamily(),
                key.marketRegime(),
                key.horizon(),
                minimum,
                samples
        );
    }

    private CohortKey normalize(ShortTermValidationCohortRequest item) {
        String family = required(item.signalFamily(), "signalFamily");
        String regime = required(item.marketRegime(), "marketRegime");
        String horizon = required(item.horizon(), "horizon");
        if (!"T1".equals(horizon) && !"T2".equals(horizon)) {
            throw new IllegalArgumentException("短线验证周期只允许 T1/T2");
        }
        return new CohortKey(family, regime, horizon);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 80) {
            throw new IllegalArgumentException(field + " 长度不能超过 80");
        }
        return normalized;
    }

    private record CohortKey(String signalFamily, String marketRegime, String horizon) {
    }
}
