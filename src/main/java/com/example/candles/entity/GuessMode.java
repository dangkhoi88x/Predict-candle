package com.example.candles.entity;

/**
 * Which game a recorded guess belongs to.
 *
 * Part of {@code guess_results}' unique constraint, not just a label: the daily challenge draws
 * a chart the same shape as a practice one, so without this a player who happened to draw
 * today's daily window in practice would find their daily already answered.
 */
public enum GuessMode {
    PRACTICE,
    DAILY
}
