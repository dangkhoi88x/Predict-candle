package com.example.candles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One answered guess, kept only for signed-in players — anonymous practice writes nothing.
 *
 * The unique constraint is the point of the (startIndex, guessNumber) columns rather than an
 * audit detail. A roundToken stays valid for its whole TTL and nothing stops a client from
 * POSTing the same one twice, so without a constraint a player could replay a correct guess
 * as many times as they liked. Together those five columns name exactly one guess in one
 * chart, so a replay collides instead of counting again.
 */
@Entity
@Table(
        name = "guess_results",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_id", "asset_id", "timeframe", "start_index", "guess_number"})
)
public class GuessResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false)
    private String timeframe;

    /** Where the chart's visible window starts — with guessNumber, identifies the question. */
    @Column(name = "start_index", nullable = false)
    private int startIndex;

    /** 1-based position within the chart's multi-guess streak. */
    @Column(name = "guess_number", nullable = false)
    private int guessNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "guessed_direction", nullable = false, length = 8)
    private Direction guessedDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_direction", nullable = false, length = 8)
    private Direction actualDirection;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GuessResult() {
    }

    public GuessResult(User user, Asset asset, String timeframe, int startIndex, int guessNumber,
                       Direction guessedDirection, Direction actualDirection) {
        this.user = user;
        this.asset = asset;
        this.timeframe = timeframe;
        this.startIndex = startIndex;
        this.guessNumber = guessNumber;
        this.guessedDirection = guessedDirection;
        this.actualDirection = actualDirection;
        this.correct = guessedDirection == actualDirection;
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

    public int getStartIndex() {
        return startIndex;
    }

    public int getGuessNumber() {
        return guessNumber;
    }

    public Direction getGuessedDirection() {
        return guessedDirection;
    }

    public Direction getActualDirection() {
        return actualDirection;
    }

    public boolean isCorrect() {
        return correct;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
