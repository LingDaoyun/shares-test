package com.aistock.research.portfolio;

import com.aistock.research.decision.InvestmentDecisionReport;
import com.aistock.research.history.DecisionHistoryEntry;
import com.aistock.research.history.ResearchHistoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final ResearchHistoryService researchHistoryService;

    public WatchlistController(WatchlistService watchlistService, ResearchHistoryService researchHistoryService) {
        this.watchlistService = watchlistService;
        this.researchHistoryService = researchHistoryService;
    }

    @GetMapping
    public List<WatchlistEntry> listEntries() {
        return watchlistService.listEntries();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistEntry add(@Valid @RequestBody WatchlistUpsertRequest request) {
        return watchlistService.add(request);
    }

    @DeleteMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String symbol) {
        watchlistService.remove(symbol);
    }

    @PostMapping("/{symbol}/analyze")
    public InvestmentDecisionReport analyze(@PathVariable String symbol) {
        return watchlistService.analyze(symbol);
    }

    @GetMapping("/{symbol}/history")
    public List<DecisionHistoryEntry> history(
            @PathVariable String symbol,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit
    ) {
        return researchHistoryService.decisions(symbol, limit);
    }
}
