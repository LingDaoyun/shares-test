package com.aistock.research.market.context;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market-scan/candidates")
public class LongTermCandidateContextController {

    private final LongTermCandidateContextService contextService;

    public LongTermCandidateContextController(LongTermCandidateContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping("/{symbol}/context")
    public LongTermCandidateContext context(
            @PathVariable String symbol,
            @RequestParam(required = false) String industry
    ) {
        return contextService.load(symbol, industry);
    }
}
