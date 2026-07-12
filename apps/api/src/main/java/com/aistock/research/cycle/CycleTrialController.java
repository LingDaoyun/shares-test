package com.aistock.research.cycle;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/cycle-trials")
public class CycleTrialController {

    private final CycleTrialService cycleTrialService;
    private final RecommendationAttestationService attestationService;

    public CycleTrialController(
            CycleTrialService cycleTrialService,
            RecommendationAttestationService attestationService
    ) {
        this.cycleTrialService = cycleTrialService;
        this.attestationService = attestationService;
    }

    @GetMapping("/report")
    public CycleTrialReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) BigDecimal leftTrialScore,
            @RequestParam(required = false) BigDecimal rightAddScore,
            @RequestParam(required = false) BigDecimal maxChaseRise,
            @RequestParam(required = false) BigDecimal minVolumeRatio
    ) {
        return attestationService.attest(
                cycleTrialService.report(limit, leftTrialScore, rightAddScore, maxChaseRise, minVolumeRatio));
    }
}
