package com.aistock.research.leader;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/potential-leaders")
public class PotentialLeaderController {

    private final PotentialLeaderService service;

    public PotentialLeaderController(PotentialLeaderService service) {
        this.service = service;
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
}
