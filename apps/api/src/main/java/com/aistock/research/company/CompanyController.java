package com.aistock.research.company;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aistock.research.research.CompanyResearchService;
import com.aistock.research.research.CompanyResearchView;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyResearchService companyResearchService;

    public CompanyController(CompanyService companyService, CompanyResearchService companyResearchService) {
        this.companyService = companyService;
        this.companyResearchService = companyResearchService;
    }

    @GetMapping
    public List<CompanyProfile> listCompanies() {
        return companyService.listCompanies();
    }

    @GetMapping("/{symbol}")
    public CompanyProfile getCompany(@PathVariable String symbol) {
        return companyService.getCompany(symbol);
    }

    @GetMapping("/{symbol}/research")
    public CompanyResearchView getCompanyResearch(@PathVariable String symbol) {
        return companyResearchService.analyze(companyService.getCompany(symbol));
    }
}
