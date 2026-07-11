package com.aistock.research.decision;

import com.aistock.research.history.ResearchHistoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentDecisionControllerTest {

    private final InvestmentDecisionService decisionService = mock(InvestmentDecisionService.class);
    private final ResearchHistoryService historyService = mock(ResearchHistoryService.class);
    private final InvestmentDecisionController controller = new InvestmentDecisionController(decisionService, historyService);

    @Test
    void postRunArchivesManualDecisionWhileGetRemainsReadOnly() {
        InvestmentDecisionReport report = mock(InvestmentDecisionReport.class);
        when(decisionService.evaluate("002714")).thenReturn(report);

        assertThat(controller.getDecision("002714")).isSameAs(report);
        verify(historyService, never()).recordDecision(report, "MANUAL_RESEARCH");

        assertThat(controller.runDecision("002714")).isSameAs(report);
        verify(historyService).recordDecision(report, "MANUAL_RESEARCH");
    }
}
