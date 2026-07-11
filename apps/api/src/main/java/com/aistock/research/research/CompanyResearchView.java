package com.aistock.research.research;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.filing.FilingEvidenceSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CompanyResearchView(
        CompanyProfile company,
        BigDecimal overallScore,
        String stage,
        String stageLabel,
        String stageReason,
        List<DimensionScore> dimensions,
        List<EvidenceTier> evidenceTiers,
        List<String> hardBlocks,
        List<String> nextActions,
        List<String> dataGaps,
        List<String> sourcePlan,
        FilingEvidenceSummary filingEvidence,
        Instant analyzedAt
) {
}
