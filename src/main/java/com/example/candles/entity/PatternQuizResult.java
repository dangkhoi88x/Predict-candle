package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;

/**
 * One player's answer to one day's pattern quiz.
 *
 * The unique constraint on (user, day) is what holds the quiz to one attempt — nothing records
 * that an attempt happened, the answer is the record of it, the same shape the daily challenge
 * uses. {@code patternId} is stored alongside the guess rather than recomputed on read: the
 * question is derivable from the day, but keeping what was actually asked means a later change
 * to the library or its thresholds cannot rewrite history someone already played.
 */
@Entity
@Table(
        name = "pattern_quiz_results",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "day"}),
        indexes = @Index(name = "idx_pattern_quiz_results_user_day", columnList = "user_id, day")
)
public class PatternQuizResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "pattern_id", nullable = false, length = 64)
    private String patternId;

    @Column(name = "guessed_pattern_id", nullable = false, length = 64)
    private String guessedPatternId;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PatternQuizResult() {
    }

    public PatternQuizResult(User user, LocalDate day, String patternId, String guessedPatternId) {
        this.user = user;
        this.day = day;
        this.patternId = patternId;
        this.guessedPatternId = guessedPatternId;
        this.correct = patternId.equals(guessedPatternId);
        this.createdAt = Instant.now();
    }

    public LocalDate getDay() {
        return day;
    }

    public String getPatternId() {
        return patternId;
    }

    public String getGuessedPatternId() {
        return guessedPatternId;
    }

    public boolean isCorrect() {
        return correct;
    }
}
