package com.aistock.research.filing;

import com.aistock.research.company.CompanyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/filings")
public class FilingController {

    private final CompanyService companyService;
    private final FilingEvidenceProvider filingEvidenceProvider;

    public FilingController(CompanyService companyService, FilingEvidenceProvider filingEvidenceProvider) {
        this.companyService = companyService;
        this.filingEvidenceProvider = filingEvidenceProvider;
    }

    @GetMapping
    public FilingEvidenceSummary getFilingEvidence(@PathVariable String symbol) {
        return filingEvidenceProvider.summarize(companyService.getCompany(symbol));
    }
}
