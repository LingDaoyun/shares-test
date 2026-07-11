package com.aistock.research.portfolio;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.decision.InvestmentDecisionReport;
import com.aistock.research.decision.InvestmentDecisionService;
import com.aistock.research.history.ResearchHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class WatchlistService {

    private final SpecialWatchlistRepository repository;
    private final CompanyService companyService;
    private final InvestmentDecisionService investmentDecisionService;
    private final ResearchHistoryService researchHistoryService;

    public WatchlistService(
            SpecialWatchlistRepository repository,
            CompanyService companyService,
            InvestmentDecisionService investmentDecisionService,
            ResearchHistoryService researchHistoryService
    ) {
        this.repository = repository;
        this.companyService = companyService;
        this.investmentDecisionService = investmentDecisionService;
        this.researchHistoryService = researchHistoryService;
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntry> listEntries() {
        return repository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toEntry)
                .toList();
    }

    public WatchlistEntry add(WatchlistUpsertRequest request) {
        String symbol = normalizeSymbol(request.symbol());
        CompanyProfile company = companyService.getCompany(symbol);
        Instant now = Instant.now();
        SpecialWatchlistEntity entity = repository.findById(symbol)
                .orElseGet(() -> SpecialWatchlistEntity.create(symbol, company.name(), request.note(), now));
        entity.update(company.name(), request.note(), now);
        return toEntry(repository.save(entity));
    }

    public void remove(String symbol) {
        repository.deleteById(normalizeSymbol(symbol));
    }

    public InvestmentDecisionReport analyze(String symbol) {
        String normalized = normalizeSymbol(symbol);
        SpecialWatchlistEntity entity = repository.findById(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该股票尚未加入特别关注"));
        InvestmentDecisionReport report = investmentDecisionService.evaluate(normalized);
        researchHistoryService.recordDecision(report, "SPECIAL_ATTENTION");
        entity.recordAnalysis(report.actionLabel(), report.decisionScore(), Instant.now());
        repository.save(entity);
        return report;
    }

    private String normalizeSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim();
        if (!normalized.matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "股票代码必须是 6 位数字");
        }
        return normalized;
    }

    private WatchlistEntry toEntry(SpecialWatchlistEntity entity) {
        return new WatchlistEntry(
                entity.getSymbol(),
                entity.getCompanyName(),
                entity.getNote(),
                entity.getLastActionLabel(),
                entity.getLastDecisionScore(),
                entity.getLastAnalyzedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
