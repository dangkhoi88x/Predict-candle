package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A paper-trading account, holding no money.
 *
 * The balance is folded out of {@link DemoTrade} on every read, so there is nothing here to
 * drift from the trades that produced it. This row exists for two other reasons: it is what a
 * trade locks, so two concurrent buys cannot both pass the same "can they afford it" check, and
 * {@code openedAt} is the mark a reset moves.
 *
 * A reset deletes nothing. It moves the mark forward, and the fold simply stops looking at
 * trades from before it — the old run stays on disk, and {@code resets} counts how many there
 * have been, which is the number any ranking built on this will have to reckon with.
 */
@Entity
@Table(name = "demo_accounts")
public class DemoAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(nullable = false)
    private int resets;

    protected DemoAccount() {
    }

    public DemoAccount(Long userId, Instant openedAt) {
        this.userId = userId;
        this.openedAt = openedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public int getResets() {
        return resets;
    }

    /** Rewinds the account to a fresh balance without losing what came before. */
    public void reset(Instant at) {
        this.openedAt = at;
        this.resets++;
    }
}
