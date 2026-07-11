package com.aistock.research.filing;

import com.aistock.research.company.CompanyProfile;

@FunctionalInterface
public interface FilingEvidenceProvider {

    FilingEvidenceSummary summarize(CompanyProfile company);
}
