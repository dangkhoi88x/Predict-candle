package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AchievementTest {

    private static Achievement.Snapshot nothing() {
        return new Achievement.Snapshot(0, 0, 0, 0, 0, 0);
    }

    private static Map<String, Achievement.Progress> byId(Achievement.Snapshot snapshot) {
        return Achievement.evaluate(snapshot).stream()
                .collect(Collectors.toMap(Achievement.Progress::id, Function.identity()));
    }

    @Test
    void anAccountThatHasDoneNothingHoldsNoBadgesButSeesAllOfThem() {
        List<Achievement.Progress> all = Achievement.evaluate(nothing());

        // The catalogue is the point: an unearned badge is a goal, and a goal you cannot see is
        // not one. Everything is returned, always.
        assertThat(all).hasSize(Achievement.values().length);
        assertThat(all).allMatch(a -> !a.earned());
        assertThat(all).allMatch(a -> a.progress() == 0);
        assertThat(all).allMatch(a -> a.target() > 0);
    }

    @Test
    void crossingAThresholdEarnsThatBadgeAndNotTheNextOneUp() {
        Map<String, Achievement.Progress> at100 =
                byId(new Achievement.Snapshot(100, 0, 0, 0, 0, 0));

        assertThat(at100.get("first-chart").earned()).isTrue();
        assertThat(at100.get("hundred-guesses").earned()).isTrue();
        assertThat(at100.get("thousand-guesses").earned()).isFalse();
        assertThat(at100.get("thousand-guesses").progress()).isEqualTo(100);
    }

    @Test
    void progressIsCappedAtTheTargetSoABarCannotOvershoot() {
        Achievement.Progress hundred = byId(new Achievement.Snapshot(9_999, 0, 0, 0, 0, 0))
                .get("hundred-guesses");

        assertThat(hundred.progress()).isEqualTo(100);
        assertThat(hundred.target()).isEqualTo(100);
        assertThat(hundred.earned()).isTrue();
    }

    @Test
    void eachBadgeReadsItsOwnNumberAndNotAnother() {
        // Only the day-streak pair may move when the day streak does. This is the mistake a
        // catalogue of near-identical entries invites: two badges wired to the same field.
        Map<String, Achievement.Progress> dayStreakOnly =
                byId(new Achievement.Snapshot(0, 0, 0, 30, 0, 0));

        assertThat(dayStreakOnly.get("week-streak").earned()).isTrue();
        assertThat(dayStreakOnly.get("month-streak").earned()).isTrue();
        assertThat(dayStreakOnly.get("daily-week").earned()).isFalse();
        assertThat(dayStreakOnly.get("daily-thirty").earned()).isFalse();
        assertThat(dayStreakOnly.get("ten-in-a-row").earned()).isFalse();
        assertThat(dayStreakOnly.get("first-chart").earned()).isFalse();

        Map<String, Achievement.Progress> dailyOnly =
                byId(new Achievement.Snapshot(0, 0, 0, 0, 7, 30));

        assertThat(dailyOnly.get("daily-week").earned()).isTrue();
        assertThat(dailyOnly.get("daily-thirty").earned()).isTrue();
        assertThat(dailyOnly.get("week-streak").earned()).isFalse();
        assertThat(dailyOnly.get("month-streak").earned()).isFalse();
    }

    @Test
    void everyBadgeHasItsOwnIdAndSomethingToSay() {
        List<Achievement.Progress> all = Achievement.evaluate(nothing());

        // Ids reach the client and a duplicate would silently draw one badge twice.
        assertThat(all.stream().map(Achievement.Progress::id).distinct().count())
                .isEqualTo(all.size());
        assertThat(all).allMatch(a -> !a.name().isBlank());
        assertThat(all).allMatch(a -> !a.description().isBlank());
    }
}
