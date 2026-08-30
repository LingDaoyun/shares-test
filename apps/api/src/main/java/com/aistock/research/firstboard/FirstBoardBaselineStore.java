package com.aistock.research.firstboard;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

interface FirstBoardBaselineStore {
    FirstBoardBaselineRun startRun(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion,
            int universeCount,
            int supportedCount,
            int excludedNameCount,
            int insufficientHistoryCount
    );

    FirstBoardBaselineSnapshot publish(FirstBoardBaselineRun run, Collection<FirstBoardBaseline> items);

    void fail(FirstBoardBaselineRun run, String errorSummary);

    Optional<FirstBoardBaselineSnapshot> findReady(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion
    );
}
