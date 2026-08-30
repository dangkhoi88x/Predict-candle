package com.example.candles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "wallet_address"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lowercased "0x"-prefixed EVM address — the account's sole identity, no password. */
    @Column(name = "wallet_address", nullable = false, length = 42)
    private String walletAddress;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /*
     * Carried over once from the browser's localStorage when an existing player first signs
     * in, so their history does not appear to reset to zero. Nullable on purpose: null means
     * "never imported", which is also what every row predating this feature holds.
     *
     * These numbers arrive from the client and cannot be verified — that is inherent to
     * where they were kept. They belong in a personal profile, not in a ranking, so the
     * stats API reports them separately from what the server recorded itself.
     */
    @Column(name = "legacy_total")
    private Long legacyTotal;

    @Column(name = "legacy_correct")
    private Long legacyCorrect;

    @Column(name = "legacy_score")
    private Long legacyScore;

    @Column(name = "legacy_best_streak")
    private Integer legacyBestStreak;

    /** Set on import; its presence is what makes the import a one-time operation. */
    @Column(name = "legacy_imported_at")
    private Instant legacyImportedAt;

    protected User() {
    }

    public User(String walletAddress, String displayName) {
        this.walletAddress = walletAddress;
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasImportedLegacyStats() {
        return legacyImportedAt != null;
    }

    public long getLegacyTotal() {
        return legacyTotal == null ? 0 : legacyTotal;
    }

    public long getLegacyCorrect() {
        return legacyCorrect == null ? 0 : legacyCorrect;
    }

    public long getLegacyScore() {
        return legacyScore == null ? 0 : legacyScore;
    }

    public int getLegacyBestStreak() {
        return legacyBestStreak == null ? 0 : legacyBestStreak;
    }

    public Instant getLegacyImportedAt() {
        return legacyImportedAt;
    }

    /**
     * Accepted once and never again — a second call would let a client raise its own score at
     * will. Callers must check {@link #hasImportedLegacyStats()} first; this guards anyway
     * because the cost of getting it wrong is unbounded.
     */
    public void importLegacyStats(long total, long correct, long score, int bestStreak) {
        if (legacyImportedAt != null) {
            throw new IllegalStateException("Legacy stats already imported for this account.");
        }
        this.legacyTotal = total;
        this.legacyCorrect = correct;
        this.legacyScore = score;
        this.legacyBestStreak = bestStreak;
        this.legacyImportedAt = Instant.now();
    }
}
