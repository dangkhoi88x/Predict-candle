package com.example.candles.entity;

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

/** One signed-in guess on a challenge. Kept out of guess_results on purpose — see V18. */
@Entity
@Table(name = "challenge_guesses",
        uniqueConstraints = @UniqueConstraint(columnNames = {"challenge_id", "user_id", "guess_number"}))
public class ChallengeGuess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "guess_number", nullable = false)
    private int guessNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "guessed_direction", length = 8)
    private Direction guessedDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_direction", nullable = false, length = 8)
    private Direction actualDirection;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChallengeGuess() {
    }

    public ChallengeGuess(Challenge challenge, User user, int guessNumber, Direction guessed, Direction actual) {
        this.challenge = challenge;
        this.user = user;
        this.guessNumber = guessNumber;
        this.guessedDirection = guessed;
        this.actualDirection = actual;
        this.correct = guessed != null && guessed == actual;
        this.createdAt = Instant.now();
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
}
