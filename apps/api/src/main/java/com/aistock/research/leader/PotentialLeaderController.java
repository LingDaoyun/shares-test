package com.aistock.research.leader;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequestMapping("/api/potential-leaders")
public class PotentialLeaderController {

    private final PotentialLeaderService service;
    private final LeaderAiReviewService aiReviewService;

    public PotentialLeaderController(
            PotentialLeaderService service,
            LeaderAiReviewService aiReviewService
    ) {
        this.service = service;
        this.aiReviewService = aiReviewService;
    }

    @GetMapping("/snapshot")
    public PotentialLeaderSnapshot snapshot(
            @RequestParam(defaultValue = "false") boolean includeGrowth,
            @RequestParam(defaultValue = "false") boolean includeStar
    ) {
        return service.snapshot(includeGrowth, includeStar);
    }

    @GetMapping("/bars/{symbol}")
    public LeaderBarSeries bars(@PathVariable String symbol) {
        return service.bars(symbol).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "该股票的日线数据不可用"));
    }

    @PostMapping("/ai-review/{lane}/{symbol}")
    public LeaderAiReview aiReview(
            @PathVariable String lane,
            @PathVariable String symbol,
            @RequestBody LeaderAiReview.Request request
    ) {
        PotentialLeaderSnapshot.Lane parsed = switch (lane.toLowerCase(Locale.ROOT)) {
            case "potential" -> PotentialLeaderSnapshot.Lane.POTENTIAL;
            case "competition" -> PotentialLeaderSnapshot.Lane.COMPETITION;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知候选通道");
        };
        return aiReviewService.review(
                parsed, symbol, request == null ? null : request.snapshotHash());
    }
}
