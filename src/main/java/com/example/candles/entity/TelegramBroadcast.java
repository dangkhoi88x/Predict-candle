package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The claim on one group announcement — see V19 for why it is written before the message is sent.
 */
@Entity
@Table(name = "telegram_broadcasts")
public class TelegramBroadcast {

    /** The day's two messages: the round opening, and the standings with hours still left. */
    public enum Kind { MORNING, EVENING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected TelegramBroadcast() {
    }

    public TelegramBroadcast(long chatId, Kind kind, LocalDate day, Instant sentAt) {
        this.chatId = chatId;
        this.kind = kind;
        this.day = day;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public long getChatId() {
        return chatId;
    }

    public Kind getKind() {
        return kind;
    }

    public LocalDate getDay() {
        return day;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
