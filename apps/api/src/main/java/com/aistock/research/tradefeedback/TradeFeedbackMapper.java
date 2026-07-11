package com.aistock.research.tradefeedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class TradeFeedbackMapper {

    private final ObjectMapper objectMapper;

    TradeFeedbackMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    TradeCaseSummary summary(TradeCaseEntity tradeCase, TradeLedgerSummary ledger) {
        return new TradeCaseSummary(
                tradeCase.getCaseId(),
                tradeCase.getSymbol(),
                tradeCase.getCompanyName(),
                tradeCase.getSourceModule(),
                tradeCase.getRecommendationAction(),
                tradeCase.getRecommendationScore(),
                tradeCase.getRuleVersion(),
                tradeCase.getRecommendedPrice(),
                tradeCase.getRecommendedAt(),
                tradeCase.getStatus(),
                ledger,
                tradeCase.getCreatedAt(),
                tradeCase.getUpdatedAt()
        );
    }

    TradeCaseDetail detail(
            TradeCaseEntity tradeCase,
            TradeLedgerSummary ledger,
            List<TradeFillEntity> fills
    ) {
        return new TradeCaseDetail(
                tradeCase.getCaseId(),
                tradeCase.getDecisionId(),
                tradeCase.getSymbol(),
                tradeCase.getCompanyName(),
                tradeCase.getSourceModule(),
                tradeCase.getRecommendationAction(),
                tradeCase.getRecommendationScore(),
                tradeCase.getRuleVersion(),
                tradeCase.getRecommendedPrice(),
                tradeCase.getRecommendedAt(),
                parsePayload(tradeCase.getRecommendationPayloadJson()),
                tradeCase.getStatus(),
                ledger,
                fills.stream().map(this::fill).toList(),
                tradeCase.getCreatedAt(),
                tradeCase.getUpdatedAt()
        );
    }

    private TradeFillView fill(TradeFillEntity fill) {
        try {
            return new TradeFillView(
                    fill.getFillId(),
                    TradeSide.valueOf(fill.getSide()),
                    fill.getExecutedAt(),
                    fill.getPrice(),
                    fill.getQuantity(),
                    fill.getCreatedAt(),
                    fill.getUpdatedAt()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("已保存的成交方向无法解析", exception);
        }
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的推荐载荷无法解析", exception);
        }
    }
}
