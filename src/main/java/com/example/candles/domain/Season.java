package com.example.candles.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * A calendar month of play, UTC — the window the leaderboard ranks over now that a board is
 * seasonal.
 *
 * A month rather than a configurable run for the reason every other boundary here is UTC-dated:
 * a season nobody has to be told the length of needs no announcement, no job to close it, and no
 * row to store. {@code of(day)} and {@code id()} are inverses, so a season is addressable in a URL
 * without anything remembering which seasons have existed.
 *
 * All-time is deliberately not a Season — it is the absence of one, which is why the board takes a
 * nullable season rather than a magic month.
 */
public record Season(YearMonth month) {

    /** The URL form, and the id the client sends back: {@code 2026-09}. */
    public String id() {
        return month.toString();
    }

    public String label() {
        return "Tháng " + month.getMonthValue() + "/" + month.getYear();
    }

    public Instant startInclusive() {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endExclusive() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Season previous() {
        return new Season(month.minusMonths(1));
    }

    public boolean contains(Instant at) {
        return !at.isBefore(startInclusive()) && at.isBefore(endExclusive());
    }

    public static Season of(Instant at) {
        return new Season(YearMonth.from(LocalDate.ofInstant(at, ZoneOffset.UTC)));
    }

    /** Null for anything that is not a month — the caller decides whether that is a refusal. */
    public static Season parse(String id) {
        try {
            return id == null ? null : new Season(YearMonth.parse(id.trim()));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
