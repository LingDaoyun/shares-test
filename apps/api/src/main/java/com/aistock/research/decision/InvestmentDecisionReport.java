package com.aistock.research.decision;

import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.financial.FinancialHistoryReport;
import com.aistock.research.review.EvidenceReviewReport;
import com.aistock.research.valuation.ValuationHistoryReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvestmentDecisionReport(
        String symbol,
        String companyName,
        String actionStage,
        String actionLabel,
        BigDecimal decisionScore,
        String actionReason,
        String complianceNote,
        int passCount,
        int watchCount,
        int blockCount,
        int failCount,
        List<InvestmentDecisionGate> gates,
        List<String> thesis,
        List<String> buyPreconditions,
        List<String> holdDisciplines,
        List<ExitTrigger> exitTriggers,
        List<String> requiredActions,
        FinancialHistoryReport financialHistory,
        ValuationHistoryReport valuationHistory,
        AgentConsensusReport consensus,
        EvidenceReviewReport evidenceReview,
        Instant generatedAt
) {
}
