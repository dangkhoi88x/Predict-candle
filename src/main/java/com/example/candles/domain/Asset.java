package com.example.candles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "assets", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetType type;

    protected Asset() {
    }

    public Asset(String symbol, String name, AssetType type) {
        this.symbol = symbol;
        this.name = name;
        this.type = type;
    }

    /**
     * Whether the pair is offered to players. False keeps the row and its candles but takes it
     * out of the picker, the sync loop and round selection.
     */
    @Column(nullable = false)
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Where the pair sits in the picker, low to high. Ties fall back to the symbol. */
    @Column(nullable = false)
    private int position = 100;

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    /** "BTCUSDT" reads as "BTC" everywhere a person sees it. */
    public String shortSymbol() {
        return symbol != null && symbol.endsWith("USDT") && symbol.length() > 4
                ? symbol.substring(0, symbol.length() - 4)
                : symbol;
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public AssetType getType() {
        return type;
    }
}
