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
    DAILY,
    /**
     * A past daily round, replayed. Deliberately not {@code DAILY}: the streak query counts
     * days with a DAILY row, so replaying an old chart must not be able to stand in for
     * turning up today.
     */
    ARCHIVE,
    /**
     * A friend's challenge link. Travels on round tokens only and is never written to
     * guess_results (whose CHECK constraint would refuse it): challenge guesses have a table of
     * their own, because a challenge's creator has seen its answers — see V18.
     */
    CHALLENGE
}
