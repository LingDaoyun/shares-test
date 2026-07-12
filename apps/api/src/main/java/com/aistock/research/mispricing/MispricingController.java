package com.aistock.research.mispricing;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/mispricing")
public class MispricingController {

    private final MispricingService mispricingService;
    private final RecommendationAttestationService attestationService;

    public MispricingController(
            MispricingService mispricingService,
            RecommendationAttestationService attestationService
    ) {
        this.mispricingService = mispricingService;
        this.attestationService = attestationService;
    }

    @GetMapping("/report")
    public MispricingReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) BigDecimal hotHeat,
            @RequestParam(required = false) BigDecimal maxPe,
            @RequestParam(required = false) BigDecimal maxPb,
            @RequestParam(required = false) BigDecimal minQuality,
            @RequestParam(required = false) Integer scanLimit
    ) {
        return attestationService.attest(
                mispricingService.report(limit, hotHeat, maxPe, maxPb, minQuality, scanLimit));
    }
}
