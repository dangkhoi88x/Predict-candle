package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DailySeedTest {

    @Test
    void theSameDayAlwaysGivesTheSameSeed() {
        assertThat(DailySeed.forDay(LocalDate.of(2026, 3, 15)))
                .isEqualTo(DailySeed.forDay(LocalDate.of(2026, 3, 15)));
    }

    @Test
    void differentDaysGiveDifferentSeeds() {
        Set<Long> seeds = new HashSet<>();
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 730; i++) {
            seeds.add(DailySeed.forDay(day.plusDays(i)).value());
        }
        assertThat(seeds).hasSize(730);
    }

    /**
     * The reason the day is mixed rather than used as a seed directly. Adjacent seeds handed
     * straight to {@link Random} draw visibly related first numbers, and every pair of days this
     * app will ever compare is adjacent — so a player could learn to guess roughly where
     * tomorrow's window sits from today's.
     */
    @Test
    void consecutiveDaysDoNotDrawNeighbouringWindows() {
        int range = 40_000; // about the size of one asset's stored history
        LocalDate day = LocalDate.of(2026, 3, 15);

        int today = new Random(DailySeed.forDay(day).value()).nextInt(range);
        int tomorrow = new Random(DailySeed.forDay(day.plusDays(1)).value()).nextInt(range);

        assertThat(Math.abs(today - tomorrow)).isGreaterThan(range / 100);
    }

    @Test
    void aYearOfDaysSpreadsAcrossTheWholeRange() {
        int range = 40_000;
        LocalDate day = LocalDate.of(2026, 1, 1);
        int low = 0;
        int high = 0;
        for (int i = 0; i < 365; i++) {
            int draw = new Random(DailySeed.forDay(day.plusDays(i)).value()).nextInt(range);
            if (draw < range / 2) low++; else high++;
        }
        // A crude balance check: a mixer that had collapsed would pile every day onto one side.
        assertThat(low).isBetween(120, 245);
        assertThat(high).isBetween(120, 245);
    }
}
