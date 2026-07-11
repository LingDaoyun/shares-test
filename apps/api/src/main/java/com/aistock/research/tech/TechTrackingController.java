package com.aistock.research.tech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/tech-tracker")
public class TechTrackingController {

    private final TechTrackingService techTrackingService;

    public TechTrackingController(TechTrackingService techTrackingService) {
        this.techTrackingService = techTrackingService;
    }

    @GetMapping("/report")
    public TechTrackingReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) BigDecimal coreMaxPe,
            @RequestParam(required = false) BigDecimal coreMaxPb,
            @RequestParam(required = false) BigDecimal hardMaxPe,
            @RequestParam(required = false) BigDecimal hardMaxPb
    ) {
        return techTrackingService.report(limit, coreMaxPe, coreMaxPb, hardMaxPe, hardMaxPb);
    }
}
