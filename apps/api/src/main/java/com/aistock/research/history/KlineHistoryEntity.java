package com.aistock.research.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "market_kline_history")
public class KlineHistoryEntity implements Persistable<String> {

    @Id
    @Column(name = "observation_id", nullable = false, length = 64)
    private String observationId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "bar_type", nullable = false, length = 32)
    private String barType;

    @Column(name = "open_price", precision = 20, scale = 6)
    private BigDecimal open;

    @Column(name = "close_price", precision = 20, scale = 6)
    private BigDecimal close;

    @Column(name = "high_price", precision = 20, scale = 6)
    private BigDecimal high;

    @Column(name = "low_price", precision = 20, scale = 6)
    private BigDecimal low;

    @Column(name = "volume", precision = 30, scale = 4)
    private BigDecimal volume;

    @Column(name = "amount", precision = 30, scale = 4)
    private BigDecimal amount;

    @Column(name = "turnover_rate", precision = 12, scale = 4)
    private BigDecimal turnoverRate;

    @Column(name = "source_name", nullable = false, length = 128)
    private String sourceName;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Transient
    private boolean newEntity;

    protected KlineHistoryEntity() {
    }

    public KlineHistoryEntity(
            String observationId,
            String symbol,
            LocalDate tradeDate,
            String barType,
            BigDecimal open,
            BigDecimal close,
            BigDecimal high,
            BigDecimal low,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            String sourceName,
            Instant observedAt
    ) {
        this.observationId = observationId;
        this.symbol = symbol;
        this.tradeDate = tradeDate;
        this.barType = barType;
        this.open = open;
        this.close = close;
        this.high = high;
        this.low = low;
        this.volume = volume;
        this.amount = amount;
        this.turnoverRate = turnoverRate;
        this.sourceName = sourceName;
        this.observedAt = observedAt;
        this.newEntity = true;
    }

    public String getObservationId() {
        return observationId;
    }

    @Override
    public String getId() {
        return observationId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newEntity = false;
    }

    public BigDecimal getClose() {
        return close;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public BigDecimal getTurnoverRate() {
        return turnoverRate;
    }
}
