package com.aistock.research.tech;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/tech-tracker")
public class TechTrackingController {

    private final TechTrackingService techTrackingService;
    private final RecommendationAttestationService attestationService;

    public TechTrackingController(
            TechTrackingService techTrackingService,
            RecommendationAttestationService attestationService
    ) {
        this.techTrackingService = techTrackingService;
        this.attestationService = attestationService;
    }

    @GetMapping("/report")
    public TechTrackingReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) BigDecimal coreMaxPe,
            @RequestParam(required = false) BigDecimal coreMaxPb,
            @RequestParam(required = false) BigDecimal hardMaxPe,
            @RequestParam(required = false) BigDecimal hardMaxPb
    ) {
        return attestationService.attest(
                techTrackingService.report(limit, coreMaxPe, coreMaxPb, hardMaxPe, hardMaxPb));
    }
}
