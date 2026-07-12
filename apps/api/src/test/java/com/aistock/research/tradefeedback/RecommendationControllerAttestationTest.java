package com.aistock.research.tradefeedback;

import com.aistock.research.cycle.CycleTrialController;
import com.aistock.research.cycle.CycleTrialReport;
import com.aistock.research.cycle.CycleTrialService;
import com.aistock.research.dailysignal.DailySignalController;
import com.aistock.research.dailysignal.DailySignalReport;
import com.aistock.research.dailysignal.DailySignalService;
import com.aistock.research.mispricing.MispricingController;
import com.aistock.research.mispricing.MispricingReport;
import com.aistock.research.mispricing.MispricingService;
import com.aistock.research.shortterm.ShortTermController;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanJobService;
import com.aistock.research.shortterm.ShortTermScanJobStatus;
import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.tech.TechTrackingController;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.tech.TechTrackingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationControllerAttestationTest {

    private final RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);

    @Test
    void shortTermReportAndCompletedScanResponsesAreAttested() {
        ShortTermService service = mock(ShortTermService.class);
        ShortTermScanJobService jobs = mock(ShortTermScanJobService.class);
        ShortTermReport report = mock(ShortTermReport.class);
        ShortTermScanJobStatus status = mock(ShortTermScanJobStatus.class);
        when(service.report(null, null, null, null, null, null, null, null, null, null)).thenReturn(report);
        when(jobs.get("job-1")).thenReturn(status);
        when(attestations.attest(report)).thenReturn(report);
        when(attestations.attest(status)).thenReturn(status);
        ShortTermController controller = new ShortTermController(service, jobs, attestations);

        assertThat(controller.report(null, null, null, null, null, null, null, null, null, null)).isSameAs(report);
        assertThat(controller.scanJob("job-1")).isSameAs(status);

        verify(attestations).attest(report);
        verify(attestations).attest(status);
    }

    @Test
    void hotTrackerResponseIsAttested() {
        TechTrackingService service = mock(TechTrackingService.class);
        TechTrackingReport report = mock(TechTrackingReport.class);
        when(service.report(null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        TechTrackingReport result = new TechTrackingController(service, attestations)
                .report(null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void mispricingResponseIsAttested() {
        MispricingService service = mock(MispricingService.class);
        MispricingReport report = mock(MispricingReport.class);
        when(service.report(null, null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        MispricingReport result = new MispricingController(service, attestations)
                .report(null, null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void cycleResponseIsAttested() {
        CycleTrialService service = mock(CycleTrialService.class);
        CycleTrialReport report = mock(CycleTrialReport.class);
        when(service.report(null, null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        CycleTrialReport result = new CycleTrialController(service, attestations)
                .report(null, null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }

    @Test
    void dailySignalResponseIsAttested() {
        DailySignalService service = mock(DailySignalService.class);
        DailySignalReport report = mock(DailySignalReport.class);
        when(service.report(null, null, null, null)).thenReturn(report);
        when(attestations.attest(report)).thenReturn(report);

        DailySignalReport result = new DailySignalController(service, attestations)
                .report(null, null, null, null);

        assertThat(result).isSameAs(report);
        verify(attestations).attest(report);
    }
}
