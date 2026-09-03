package com.example.candles.domain;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerScoreTest {

    private static List<Boolean> flags(String pattern) {
        return pattern.chars().mapToObj(c -> c == '1').toList();
    }

    @Test
    void countsNothingForNoResults() {
        PlayerScore s = PlayerScore.of(List.of());
        assertEquals(0, s.total());
        assertEquals(0, s.correct());
        assertEquals(0, s.bestStreak());
        assertEquals(0, s.score());
    }

    @Test
    void firstCorrectGuessScoresBasePointsOnly() {
        assertEquals(10, PlayerScore.of(flags("1")).score());
    }

    @Test
    void bonusGrowsWithTheStreak() {
        // 10, then +2 per prior step: 10 + 12 + 14 = 36
        assertEquals(36, PlayerScore.of(flags("111")).score());
    }

    @Test
    void bonusStopsGrowingAfterTenSteps() {
        // Guesses 11 and 12 are both capped at 30, so the last two add 60 between them.
        long twelve = PlayerScore.of(flags("111111111111")).score();
        long eleven = PlayerScore.of(flags("11111111111")).score();
        long ten = PlayerScore.of(flags("1111111111")).score();
        assertEquals(30, eleven - ten);
        assertEquals(30, twelve - eleven);
    }

    @Test
    void wrongGuessResetsTheStreakButKeepsTheScore() {
        // "11" scores 22, the miss adds nothing, then the streak restarts at base points.
        assertEquals(32, PlayerScore.of(flags("1101")).score());
        assertEquals(2, PlayerScore.of(flags("1101")).bestStreak());
    }

    @Test
    void bestStreakIsTheLongestRunNotTheLast() {
        PlayerScore s = PlayerScore.of(flags("1110101"));
        assertEquals(3, s.bestStreak());
        assertEquals(7, s.total());
        assertEquals(5, s.correct());
    }

    @Test
    void currentStreakIsTheRunStillGoing() {
        assertEquals(2, PlayerScore.of(flags("101011")).currentStreak());
        assertEquals(0, PlayerScore.of(flags("1110")).currentStreak());
    }

    @Test
    void missesAloneScoreNothing() {
        PlayerScore s = PlayerScore.of(flags("0000"));
        assertEquals(4, s.total());
        assertEquals(0, s.correct());
        assertEquals(0, s.bestStreak());
        assertEquals(0, s.score());
    }
}
