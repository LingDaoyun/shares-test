package com.aistock.research.shortterm.validation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/short-term/validation")
public class ShortTermValidationController {

    private final ShortTermValidationQueryService queryService;

    public ShortTermValidationController(ShortTermValidationQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/summaries")
    public List<ShortTermValidationSummary> summaries(
            @RequestBody(required = false) ShortTermValidationBatchRequest request
    ) {
        return queryService.summaries(request);
    }
}
