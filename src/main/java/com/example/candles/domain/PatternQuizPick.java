package com.example.candles.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Which pattern the day asks about, and which four names it offers — both derived from the
 * date, so every player gets the same question and the server can recheck an answer without
 * having stored anything.
 *
 * Pure over the list of pattern ids. The library is the only input besides the day, which is
 * what lets the awkward parts — that the order must be stable, and that the correct answer must
 * not always sit in the same slot — be tested without a database.
 *
 * {@link #orderedCandidates} returns the whole library rather than one pattern because a
 * pattern can have no clean example anywhere in the stored history: the caller walks this order
 * until one does, the same way {@code selectDailyRound} walks on from its seeded asset. The
 * day still decides; the data only decides how far down the list it has to look.
 */
public final class PatternQuizPick {

    private PatternQuizPick() {
    }

    /** The library in a day-specific order. Position 0 is the day's first choice of pattern. */
    public static List<String> orderedCandidates(LocalDate day, List<String> allPatternIds) {
        List<String> shuffled = new ArrayList<>(allPatternIds);
        Collections.shuffle(shuffled, new Random(DailySeed.forDay(day).value()));
        return List.copyOf(shuffled);
    }

    /**
     * {@code count} names including the right one, in an order that is stable for the day.
     *
     * Seeded separately from the candidate order so the answer does not land in the same slot
     * every day — with one stream a player who noticed the correct name was always third would
     * never have to look at the chart again.
     */
    public static List<String> choicesFor(LocalDate day, String correctId,
                                          List<String> allPatternIds, int count) {
        List<String> others = new ArrayList<>(allPatternIds);
        others.remove(correctId);
        Random random = new Random(DailySeed.forDay(day).value() * 31 + correctId.hashCode());
        Collections.shuffle(others, random);

        List<String> choices = new ArrayList<>(others.subList(0, Math.min(count - 1, others.size())));
        choices.add(correctId);
        Collections.shuffle(choices, random);
        return List.copyOf(choices);
    }
}
