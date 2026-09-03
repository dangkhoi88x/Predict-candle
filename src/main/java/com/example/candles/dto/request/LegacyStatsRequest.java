package com.example.candles.dto.request;

import jakarta.validation.constraints.Min;

/** The shape app.js keeps in localStorage under candleGuess.stats.v1. */
public record LegacyStatsRequest(
        @Min(0) long total,
        @Min(0) long correct,
        @Min(0) long score,
        @Min(0) int bestStreak
) {
}
