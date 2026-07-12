package com.aistock.research.dailysignal;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/daily-signals")
public class DailySignalController {

    private final DailySignalService dailySignalService;
    private final RecommendationAttestationService attestationService;

    public DailySignalController(
            DailySignalService dailySignalService,
            RecommendationAttestationService attestationService
    ) {
        this.dailySignalService = dailySignalService;
        this.attestationService = attestationService;
    }

    @GetMapping("/report")
    public DailySignalReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer techLimit,
            @RequestParam(required = false) Integer mispricingLimit,
            @RequestParam(required = false) BigDecimal hotHeat
    ) {
        return attestationService.attest(
                dailySignalService.report(limit, techLimit, mispricingLimit, hotHeat));
    }
}
