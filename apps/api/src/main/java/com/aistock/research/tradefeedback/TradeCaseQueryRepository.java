package com.aistock.research.tradefeedback;

import java.time.Instant;
import java.util.List;

public interface TradeCaseQueryRepository {

    List<TradeCaseEntity> findCasePage(
            String status,
            String symbol,
            Instant beforeCreatedAt,
            String beforeCaseId,
            int limit
    );
}
