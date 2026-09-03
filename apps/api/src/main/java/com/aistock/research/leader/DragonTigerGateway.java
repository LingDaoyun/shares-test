package com.aistock.research.leader;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface DragonTigerGateway {

    DragonTigerBatch fetch(LocalDate scanTradeDate, Collection<String> symbols);

    record DragonTigerBatch(
            LocalDate latestDisclosureDate,
            Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> evidenceBySymbol,
            List<String> dataGaps
    ) {
        public DragonTigerBatch {
            evidenceBySymbol = evidenceBySymbol == null ? Map.of() : Map.copyOf(evidenceBySymbol);
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }
}
