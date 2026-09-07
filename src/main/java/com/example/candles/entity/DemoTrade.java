package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One executed paper trade — the only thing this feature stores.
 *
 * Quantity and price are what happened; the notional is their product and is deliberately not a
 * column, so the two can never disagree with a third. Price is whatever the server read from the
 * live feed at the moment of the trade and never anything the client sent.
 */
@Entity
@Table(name = "demo_trades", indexes = @Index(name = "idx_demo_trades_user_time", columnList = "user_id, created_at"))
public class DemoTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TradeSide side;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal price;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal fee;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DemoTrade() {
    }

    public DemoTrade(Long userId, Asset asset, TradeSide side,
                     BigDecimal quantity, BigDecimal price, BigDecimal fee, Instant at) {
        this.userId = userId;
        this.asset = asset;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.fee = fee;
        this.createdAt = at;
    }

    public Asset getAsset() {
        return asset;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
