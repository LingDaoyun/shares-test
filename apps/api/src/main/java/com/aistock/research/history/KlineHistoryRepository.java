package com.aistock.research.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface KlineHistoryRepository extends JpaRepository<KlineHistoryEntity, String> {

    @Query("""
            select row.observationId
            from KlineHistoryEntity row
            where row.symbol = :symbol
              and row.tradeDate between :startDate and :endDate
            """)
    List<String> findObservationIdsBySymbolAndTradeDateBetween(
            @Param("symbol") String symbol,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
