package com.aistock.research.shortterm;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final ShortTermLimitUpBoardService limitUpBoardService;
    private final ShortTermCatchUpService catchUpService;

    public ShortTermController(
            ShortTermService shortTermService,
            ShortTermScanJobService scanJobService,
            RecommendationAttestationService attestationService,
            ShortTermLimitUpBoardService limitUpBoardService,
            ShortTermCatchUpService catchUpService
    ) {
        this.shortTermService = shortTermService;
        this.scanJobService = scanJobService;
        this.attestationService = attestationService;
        this.limitUpBoardService = limitUpBoardService;
        this.catchUpService = catchUpService;
    }

    @GetMapping("/limit-up-board")
    public ShortTermLimitUpBoardSnapshot limitUpBoard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return limitUpBoardService.snapshot(date);
    }

    @GetMapping("/limit-up-board/industry")
    public ShortTermLimitUpIndustrySnapshot limitUpIndustry(
            @RequestParam String industry,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return limitUpBoardService.industrySnapshot(industry, date);
    }

    @GetMapping("/limit-up-board/industry/catch-up")
    public ShortTermCatchUpSnapshot catchUp(
            @RequestParam String industry,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String leaderSymbol,
            @RequestParam(required = false) String boardCode
    ) {
        return catchUpService.snapshot(industry, date, leaderSymbol, boardCode);
    }

    @PostMapping("/catch-up/follow-up")
    public ShortTermCatchUpFollowUpSnapshot catchUpFollowUp(@RequestBody ShortTermCatchUpFollowUpRequest request) {
        return catchUpService.followUp(request);
    }

    @GetMapping("/industry-fund-leaders")
    public ShortTermIndustryLeaderSnapshot industryFundLeaders(
            @RequestParam String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "INFLOW") String flow,
            @RequestParam(required = false, defaultValue = "20") Integer limit
    ) {
        return shortTermService.industryLeaderSnapshot(code, name, flow, limit == null ? 20 : limit);
    }

    @GetMapping("/market-fund-direction")
    public ShortTermMarketFundDirection marketFundDirection() {
        return shortTermService.marketFundDirection();
    }

    @GetMapping("/market-breadth")
    public ShortTermMarketBreadth marketBreadth() {
        return limitUpBoardService.marketBreadth();
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
