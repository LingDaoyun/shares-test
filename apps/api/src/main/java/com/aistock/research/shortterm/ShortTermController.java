package com.aistock.research.shortterm;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/short-term")
public class ShortTermController {

    private final ShortTermService shortTermService;
    private final ShortTermScanJobService scanJobService;
    private final RecommendationAttestationService attestationService;

    public ShortTermController(
            ShortTermService shortTermService,
            ShortTermScanJobService scanJobService,
            RecommendationAttestationService attestationService
    ) {
        this.shortTermService = shortTermService;
        this.scanJobService = scanJobService;
        this.attestationService = attestationService;
    }

    @GetMapping("/report")
    public ShortTermReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer scanLimit,
            @RequestParam(required = false) Integer klineLimit,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxPricePerShare,
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
                maxPricePerShare,
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
}
