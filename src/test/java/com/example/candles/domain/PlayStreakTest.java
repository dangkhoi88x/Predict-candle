package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayStreakTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /** Days ago, newest first — the shape the query hands over. */
    private static List<LocalDate> daysAgo(int... offsets) {
        return Arrays.stream(offsets).mapToObj(TODAY::minusDays).toList();
    }

    private static PlayStreak of(int... offsets) {
        return PlayStreak.of(daysAgo(offsets), TODAY);
    }

    @Test
    void anAccountThatHasNeverPlayedHasNoStreak() {
        PlayStreak s = PlayStreak.of(List.of(), TODAY);
        assertEquals(0, s.current());
        assertEquals(0, s.best());
        assertEquals(0, s.daysPlayed());
        assertFalse(s.playedToday());
    }

    @Test
    void consecutiveDaysEndingTodayCountUp() {
        PlayStreak s = of(0, 1, 2);
        assertEquals(3, s.current());
        assertEquals(3, s.best());
        assertEquals(3, s.daysPlayed());
        assertTrue(s.playedToday());
    }

    @Test
    void yesterdayStillHoldsTheStreakOpen() {
        // The whole point of the grace: at 00:01 UTC nobody has played today yet, and their
        // streak is not broken until a full day has gone by.
        PlayStreak s = of(1, 2, 3);
        assertEquals(3, s.current());
        assertFalse(s.playedToday());
    }

    @Test
    void aMissedDayBreaksTheCurrentStreakButNotTheBest() {
        // Played four days in a row, then nothing for two days.
        PlayStreak s = of(2, 3, 4, 5);
        assertEquals(0, s.current());
        assertEquals(4, s.best());
        assertEquals(4, s.daysPlayed());
    }

    @Test
    void bestIsTheLongestRunAnywhereInTheHistory() {
        // Two today-ish days, a gap, then a five-day run further back.
        PlayStreak s = of(0, 1, 5, 6, 7, 8, 9);
        assertEquals(2, s.current());
        assertEquals(5, s.best());
    }

    @Test
    void aSingleDayIsAStreakOfOne() {
        PlayStreak s = of(0);
        assertEquals(1, s.current());
        assertEquals(1, s.best());
        assertTrue(s.playedToday());
    }

    @Test
    void playingOnlyLongAgoLeavesTheCurrentStreakAtZero() {
        PlayStreak s = of(40, 41);
        assertEquals(0, s.current());
        assertEquals(2, s.best());
        assertFalse(s.playedToday());
    }
}
