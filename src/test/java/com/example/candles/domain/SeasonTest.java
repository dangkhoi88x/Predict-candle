package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/** A season is a UTC month and nothing is stored about it, so its edges are the whole contract. */
class SeasonTest {

    private static final Season SEPTEMBER = new Season(YearMonth.of(2026, 9));

    @Test
    void anIdIsAMonthAndParsesBackToTheSameSeason() {
        assertThat(SEPTEMBER.id()).isEqualTo("2026-09");
        assertThat(Season.parse("2026-09")).isEqualTo(SEPTEMBER);
        assertThat(Season.parse(" 2026-09 ")).isEqualTo(SEPTEMBER);
        assertThat(SEPTEMBER.label()).isEqualTo("Tháng 9/2026");
    }

    @Test
    void anythingThatIsNotAMonthIsNull() {
        assertThat(Season.parse(null)).isNull();
        assertThat(Season.parse("all")).isNull();
        assertThat(Season.parse("2026-13")).isNull();
        assertThat(Season.parse("2026-09-15")).isNull();
    }

    /** Midnight UTC either end: the boundary every other day in this app is cut on. */
    @Test
    void theMonthRunsFromItsFirstMidnightToTheNextMonthsFirstMidnight() {
        assertThat(SEPTEMBER.startInclusive()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(SEPTEMBER.endExclusive()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(SEPTEMBER.contains(Instant.parse("2026-09-01T00:00:00Z"))).isTrue();
        assertThat(SEPTEMBER.contains(Instant.parse("2026-09-30T23:59:59Z"))).isTrue();
        assertThat(SEPTEMBER.contains(Instant.parse("2026-10-01T00:00:00Z"))).isFalse();
        assertThat(SEPTEMBER.contains(Instant.parse("2026-08-31T23:59:59Z"))).isFalse();
    }

    @Test
    void aCallIsInTheSeasonItsUtcDayBelongsTo() {
        // 07:00 in Vietnam on the 1st is still the last hour of the previous month in UTC.
        assertThat(Season.of(Instant.parse("2026-08-31T23:30:00Z"))).isEqualTo(SEPTEMBER.previous());
        assertThat(Season.of(Instant.parse("2026-09-15T12:00:00Z"))).isEqualTo(SEPTEMBER);
        assertThat(SEPTEMBER.previous().id()).isEqualTo("2026-08");
        assertThat(new Season(YearMonth.of(2026, 1)).previous().id()).isEqualTo("2025-12");
    }
}
