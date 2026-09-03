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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One call on one live round.
 *
 * A live round is not a row anywhere: it *is* a real candle, named by its open time. The clock
 * decides which round is open and the candle Binance eventually delivers decides how it ended,
 * so there is no round table, no settlement job and no state machine — a result is a comparison
 * between this row's `openTime` and the stored candle at that time, computed when someone asks.
 *
 * Which makes the unique constraint the whole integrity story, exactly as it is for
 * {@link GuessResult}: one call per account per round, enforced by the database rather than by
 * a check the application could forget.
 */
@Entity
@Table(
        name = "live_predictions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_live_prediction_per_round",
                columnNames = {"user_id", "asset_id", "timeframe", "open_time"}),
        indexes = {
                /* Reading a round counts both sides of the crowd for one (asset, timeframe, open_time). */
                @Index(name = "idx_live_predictions_round", columnList = "asset_id, timeframe, open_time"),
                /* A player's own history is read newest first. */
                @Index(name = "idx_live_predictions_user_time", columnList = "user_id, open_time desc"),
        }
)
public class LivePrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false, length = 16)
    private String timeframe;

    /** The open time of the candle being called — this is the round's identity. */
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LivePrediction() {
    }

    public LivePrediction(User user, Asset asset, String timeframe, Instant openTime, Direction direction) {
        this.user = user;
        this.asset = asset;
        this.timeframe = timeframe;
        this.openTime = openTime;
        this.direction = direction;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Asset getAsset() {
        return asset;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public Instant getOpenTime() {
        return openTime;
    }

    public Direction getDirection() {
        return direction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
