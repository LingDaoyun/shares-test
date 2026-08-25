package com.aistock.research.market;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overseas-market")
public class OverseasMarketController {

    private final OverseasMarketBoardService overseasMarketBoardService;

    public OverseasMarketController(OverseasMarketBoardService overseasMarketBoardService) {
        this.overseasMarketBoardService = overseasMarketBoardService;
    }

    /** 外围看板是客观数据行情聚合，不携带买卖建议，与高股息/涨停看板一致不做存证。 */
    @GetMapping("/board")
    public OverseasMarketBoardSnapshot board() {
        return overseasMarketBoardService.snapshot();
    }
}
