package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A finished practice chart turned into a link. See V18 for why it holds what it holds. */
@Entity
@Table(name = "challenges")
public class Challenge {

    @Id
    @Column(length = 16)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false)
    private String timeframe;

    @Column(name = "start_index", nullable = false)
    private int startIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private User creator;

    @Column(name = "creator_name", nullable = false, length = 64)
    private String creatorName;

    @Column(name = "creator_correct", nullable = false)
    private int creatorCorrect;

    @Column(name = "total_guesses", nullable = false)
    private int totalGuesses;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Challenge() {
    }

    public Challenge(String id, Asset asset, String timeframe, int startIndex, User creator,
                     String creatorName, int creatorCorrect, int totalGuesses) {
        this.id = id;
        this.asset = asset;
        this.timeframe = timeframe;
        this.startIndex = startIndex;
        this.creator = creator;
        this.creatorName = creatorName;
        this.creatorCorrect = creatorCorrect;
        this.totalGuesses = totalGuesses;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
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

    public User getCreator() {
        return creator;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public int getCreatorCorrect() {
        return creatorCorrect;
    }

    public int getTotalGuesses() {
        return totalGuesses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
