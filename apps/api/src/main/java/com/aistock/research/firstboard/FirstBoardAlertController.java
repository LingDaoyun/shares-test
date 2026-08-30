package com.aistock.research.firstboard;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/first-board-alert")
public class FirstBoardAlertController {

    private final FirstBoardAlertService service;

    public FirstBoardAlertController(FirstBoardAlertService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public FirstBoardAlertSnapshot snapshot(
            @RequestParam(defaultValue = "false") boolean includeGrowth,
            @RequestParam(defaultValue = "false") boolean includeStar
    ) {
        return service.snapshot(includeGrowth, includeStar);
    }

    @GetMapping("/bars/{symbol}")
    public FirstBoardBarSeries bars(@PathVariable String symbol) {
        return service.bars(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该股票的 T-1 图表数据不可用"));
    }
}
