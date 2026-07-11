package com.aistock.research.portfolio;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.CompanyService;
import com.aistock.research.decision.InvestmentDecisionReport;
import com.aistock.research.decision.InvestmentDecisionService;
import com.aistock.research.history.ResearchHistoryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistServiceTest {

    private final SpecialWatchlistRepository repository = mock(SpecialWatchlistRepository.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final InvestmentDecisionService decisionService = mock(InvestmentDecisionService.class);
    private final ResearchHistoryService researchHistoryService = mock(ResearchHistoryService.class);
    private final WatchlistService service = new WatchlistService(repository, companyService, decisionService, researchHistoryService);

    @Test
    void externalAnalysisDoesNotHoldAServiceTransactionOpen() throws Exception {
        Method analyze = WatchlistService.class.getMethod("analyze", String.class);

        assertThat(analyze.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    void addingSpecialAttentionDoesNotReadOrModifyTheRecommendationPool() {
        when(companyService.getCompany("600036")).thenReturn(company("600036", "招商银行"));
        when(repository.save(any(SpecialWatchlistEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WatchlistEntry entry = service.add(new WatchlistUpsertRequest("600036", "长期跟踪息差和资产质量"));

        assertThat(entry.symbol()).isEqualTo("600036");
        assertThat(entry.companyName()).isEqualTo("招商银行");
        assertThat(entry.note()).contains("息差");
        verify(companyService, never()).listCompanies();
    }

    @Test
    void listOnlyReturnsExplicitlySavedSpecialAttentionItems() {
        SpecialWatchlistEntity saved = SpecialWatchlistEntity.create(
                "002714",
                "牧原股份",
                "长期周期观察",
                Instant.parse("2026-07-11T08:00:00Z")
        );
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(saved));

        List<WatchlistEntry> entries = service.listEntries();

        assertThat(entries).extracting(WatchlistEntry::symbol).containsExactly("002714");
        verify(companyService, never()).listCompanies();
    }

    @Test
    void activeAnalysisRunsTheFullDecisionChainAndRecordsTheResult() {
        SpecialWatchlistEntity saved = SpecialWatchlistEntity.create(
                "002714",
                "牧原股份",
                "长期周期观察",
                Instant.parse("2026-07-11T08:00:00Z")
        );
        InvestmentDecisionReport report = mock(InvestmentDecisionReport.class);
        when(report.actionLabel()).thenReturn("证据复核");
        when(report.decisionScore()).thenReturn(new BigDecimal("68.50"));
        when(repository.findById("002714")).thenReturn(Optional.of(saved));
        when(repository.save(any(SpecialWatchlistEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(decisionService.evaluate("002714")).thenReturn(report);

        InvestmentDecisionReport result = service.analyze("002714");

        assertThat(result).isSameAs(report);
        assertThat(saved.getLastActionLabel()).isEqualTo("证据复核");
        assertThat(saved.getLastDecisionScore()).isEqualByComparingTo("68.50");
        assertThat(saved.getLastAnalyzedAt()).isNotNull();
        verify(decisionService).evaluate("002714");
        verify(researchHistoryService).recordDecision(report, "SPECIAL_ATTENTION");
        verify(repository).save(saved);
    }

    private CompanyProfile company(String symbol, String name) {
        return new CompanyProfile(
                symbol,
                name,
                "上交所",
                "银行",
                "VALUE",
                BigDecimal.ZERO,
                new BigDecimal("36.83"),
                BigDecimal.ZERO,
                new BigDecimal("6.15"),
                new BigDecimal("0.83"),
                BigDecimal.ZERO,
                new BigDecimal("900000000"),
                "https://quote.example.com/" + symbol,
                "测试行情",
                "2026-07-11T08:00:00Z",
                null,
                null,
                true,
                List.of(),
                List.of(),
                Map.of(),
                List.of()
        );
    }
}
