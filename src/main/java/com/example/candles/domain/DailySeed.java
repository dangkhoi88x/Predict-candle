package com.example.candles.domain;

import java.time.LocalDate;

/**
 * The number a UTC day turns into, and the whole of what a daily round is derived from.
 *
 * Same idea as {@link LiveRound#at}: the round is named from the calendar rather than kept in a
 * table, so two servers, a page reload and a player who arrives at 23:50 all agree on which
 * chart today is without a generation job that has to run exactly once at midnight — and
 * without a row that could be missing when someone opens the page.
 *
 * A wrapper rather than a bare long because the value is about to be handed to a method that
 * also takes indexes and counts, and a long in that position is easy to pass the wrong thing to.
 *
 * The day is put through splitmix64's finalizer instead of being used as the seed directly.
 * {@link java.util.Random} scrambles its seed with a multiplicative generator whose first
 * outputs for adjacent seeds are visibly related, and the seeds here are always adjacent —
 * yesterday and today differ by one. Without the mixing, consecutive days would tend to draw
 * neighbouring windows out of the same asset's history, which is the one pattern a player must
 * not be able to spot.
 */
public record DailySeed(long value) {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    public static DailySeed forDay(LocalDate day) {
        long z = day.toEpochDay() + GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new DailySeed(z ^ (z >>> 31));
    }
}
