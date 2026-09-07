package com.example.candles.domain;

import java.time.LocalDate;

/**
 * A UTC day and the number that day's challenge is known by — the "#128" a player puts on a
 * share card, and the only reason a number exists at all: a date is a worse thing to compare
 * over, because two people in different time zones disagree about what today is called.
 *
 * {@link #FIRST_DAY} is the origin of the numbering and nothing else. Moving it renumbers every
 * round that has ever been shared, which is why it is a constant here rather than a setting —
 * there is no deployment that wants a different answer, only one that would break every link
 * already posted.
 */
public record DailyRound(LocalDate day, long number) {

    /** Public so the archive can refuse a day that predates round #1. */
    public static final LocalDate FIRST_DAY = LocalDate.of(2026, 1, 1);

    public static DailyRound forDay(LocalDate day) {
        return new DailyRound(day, day.toEpochDay() - FIRST_DAY.toEpochDay() + 1);
    }
}
