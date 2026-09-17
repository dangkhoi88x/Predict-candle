package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One thing that went wrong, or a run of the same thing — see V21 for why these are stored.
 *
 * {@code where} is a SQL keyword in enough dialects to be worth avoiding, hence {@code where_at}.
 */
@Entity
@Table(name = "app_errors")
public class AppError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "where_at", nullable = false, length = 200)
    private String whereAt;

    @Column(nullable = false, length = 300)
    private String summary;

    @Column(name = "first_at", nullable = false)
    private Instant firstAt;

    @Column(name = "last_at", nullable = false)
    private Instant lastAt;

    @Column(nullable = false)
    private int count;

    protected AppError() {
    }

    public AppError(String source, String whereAt, String summary, Instant at) {
        this.source = source;
        this.whereAt = whereAt;
        this.summary = summary;
        this.firstAt = at;
        this.lastAt = at;
        this.count = 1;
    }

    /** Another of the same failure: the row keeps when it started and moves when it last happened. */
    public void repeated(Instant at) {
        this.lastAt = at;
        this.count++;
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getWhereAt() {
        return whereAt;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getFirstAt() {
        return firstAt;
    }

    public Instant getLastAt() {
        return lastAt;
    }

    public int getCount() {
        return count;
    }
}
