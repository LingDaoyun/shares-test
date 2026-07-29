package com.aistock.research.longterm;

import java.util.List;

public record LongTermInvestmentAssessment(
        String strategyVersion,
        String modelCode,
        String modelLabel,
        String status,
        String statusLabel,
        LongTermFactorScores factorScores,
        LongTermFinancialQuality financialQuality,
        LongTermValuationExpectation valuation,
        LongTermPositionDiscipline positionDiscipline,
        LongTermLogicAudit logicAudit,
        List<String> evidence,
        List<String> risks,
        List<String> dataGaps
) {
}
