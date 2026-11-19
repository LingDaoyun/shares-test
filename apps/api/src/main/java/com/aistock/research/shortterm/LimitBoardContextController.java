package com.aistock.research.shortterm;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/short-term/limit-board-context")
public class LimitBoardContextController {
    private final LimitBoardContextService service;

    public LimitBoardContextController(LimitBoardContextService service) {
        this.service = service;
    }

    @GetMapping
    public LimitBoardContextSnapshot snapshot(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.snapshot(date);
    }
}
