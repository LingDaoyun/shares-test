package com.aistock.research.shortterm;

import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotStore;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/short-term")
public class ShortTermController {

    private final ShortTermService shortTermService;
    private final ShortTermScanJobService scanJobService;
    private final RecommendationAttestationService attestationService;
    private final TradingClockService tradingClockService;
    private final ShortTermScheduledSnapshotStore snapshotStore;
    private final ShortTermAutomationSettings automationSettings;

    public ShortTermController(
            ShortTermService shortTermService,
            ShortTermScanJobService scanJobService,
            RecommendationAttestationService attestationService,
            TradingClockService tradingClockService,
            ShortTermScheduledSnapshotStore snapshotStore,
            ShortTermAutomationSettings automationSettings
    ) {
        this.shortTermService = shortTermService;
        this.scanJobService = scanJobService;
        this.attestationService = attestationService;
        this.tradingClockService = tradingClockService;
        this.snapshotStore = snapshotStore;
        this.automationSettings = automationSettings;
    }

    @GetMapping("/report")
    public ShortTermReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer scanLimit,
            @RequestParam(required = false) Integer klineLimit,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxPe,
            @RequestParam(required = false) BigDecimal maxPb,
            @RequestParam(required = false) BigDecimal minVolumeRatio,
            @RequestParam(required = false) BigDecimal maxEntryRise,
            @RequestParam(required = false) BigDecimal maxDistanceToMa20,
            @RequestParam(required = false) BigDecimal minFinancialScore,
            @RequestParam(required = false) Boolean allowChiNext
    ) {
        return attestationService.attest(shortTermService.report(
                limit,
                scanLimit,
                klineLimit,
                minAmount,
                maxPe,
                maxPb,
                minVolumeRatio,
                maxEntryRise,
                maxDistanceToMa20,
                minFinancialScore,
                allowChiNext
        ));
    }

    @PostMapping("/scan-jobs")
    public ShortTermScanJobStatus startScanJob(@RequestBody(required = false) ShortTermScanRequest request) {
        return scanJobService.start(request);
    }

    @GetMapping("/scan-jobs/{jobId}")
    public ShortTermScanJobStatus scanJob(@PathVariable String jobId) {
        return attestationService.attest(scanJobService.get(jobId));
    }

    @GetMapping("/scheduled-snapshots/latest")
    public ShortTermScheduledSnapshot latestScheduledSnapshot() {
        LocalDate today = tradingClockService.currentMarketDate();
        return snapshotStore.latest(today)
                .map(this::attestEligibleSnapshot)
                .orElseGet(() -> ShortTermScheduledSnapshot.waiting(
                        today, "等待 " + automationSettings.preselectCron() + " 自动预选"));
    }

    private ShortTermScheduledSnapshot attestEligibleSnapshot(ShortTermScheduledSnapshot snapshot) {
        if (snapshot.status() != ShortTermSnapshotStatus.FINAL_READY || snapshot.report() == null) {
            return snapshot;
        }
        return snapshot.withReport(attestationService.attest(snapshot.report()));
    }
}
