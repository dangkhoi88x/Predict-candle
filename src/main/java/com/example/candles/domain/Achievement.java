package com.example.candles.domain;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The badge catalogue, and the whole of it — definition and rule in one place.
 *
 * **Nothing is stored.** A badge is not a row that gets written when a player crosses a line;
 * it is a question asked of their history, the same way {@link PlayStreak} and the daily
 * challenge's attempt state are. That buys three things a table would not:
 *
 * A player's badges cannot drift from what they actually did. There is no write path to forget,
 * no "award" that fires twice or not at all, and deleting an account's results takes its badges
 * with them rather than leaving trophies for guesses that no longer exist.
 *
 * A badge added later is awarded retroactively to everyone who already qualified, which is
 * almost always what you want and is otherwise a backfill migration per badge.
 *
 * And thresholds can be tuned. With a table, lowering one leaves the players who were already
 * past it unbadged until they play again, and raising one leaves badges nobody can now earn
 * standing on accounts that no longer qualify.
 *
 * What this gives up is a real thing: there is no *when*. The profile cannot say "earned on
 * March 3rd", and the server cannot tell that a badge is newly earned this request — the client
 * remembers what it last showed, which is a per-browser convenience and honestly labelled as
 * one. If badge history ever needs to survive a change of device, that is the point to add a
 * table of first-earned timestamps *beside* this, never instead of it: the rule stays here.
 *
 * Progress is reported even when unearned, because that is the mechanic. A badge nobody can see
 * themselves approaching is not a goal, and goals in reach are what the streak leaves behind
 * when it breaks.
 */
public enum Achievement {

    FIRST_CHART("first-chart", "Ván đầu tiên", "Chơi hết một biểu đồ",
            5, Snapshot::guesses),
    HUNDRED_GUESSES("hundred-guesses", "Trăm lượt", "Đoán 100 nến",
            100, Snapshot::guesses),
    THOUSAND_GUESSES("thousand-guesses", "Nghìn lượt", "Đoán 1000 nến",
            1000, Snapshot::guesses),

    TEN_IN_A_ROW("ten-in-a-row", "Mười nến liền", "Đoán đúng 10 nến liên tiếp",
            10, Snapshot::bestGuessStreak),
    THOUSAND_POINTS("thousand-points", "Nghìn điểm", "Đạt 1000 điểm",
            1000, Snapshot::score),

    WEEK_STREAK("week-streak", "Tuần đều đặn", "Chơi 7 ngày liên tiếp",
            7, Snapshot::bestDayStreak),
    MONTH_STREAK("month-streak", "Tháng đều đặn", "Chơi 30 ngày liên tiếp",
            30, Snapshot::bestDayStreak),

    DAILY_WEEK("daily-week", "Thử thách 7 ngày", "Chơi thử thách hằng ngày 7 ngày liên tiếp",
            7, Snapshot::bestDailyStreak),
    DAILY_THIRTY("daily-thirty", "30 thử thách", "Chơi 30 lượt thử thách hằng ngày",
            30, Snapshot::dailyDaysPlayed);

    /**
     * Every number a badge may be judged on. A record rather than the repositories themselves,
     * so the rules above stay a pure function with nothing to mock — the awkward part of a
     * catalogue like this is the thresholds, and this is the shape that makes them testable.
     */
    public record Snapshot(
            long guesses,
            int bestGuessStreak,
            long score,
            int bestDayStreak,
            int bestDailyStreak,
            long dailyDaysPlayed
    ) {
    }

    /** {@code progress} is capped at {@code target}, so a bar never overshoots its own end. */
    public record Progress(String id, String name, String description,
                           long progress, long target, boolean earned) {
    }

    private final String id;
    private final String name;
    private final String description;
    private final long target;
    private final ToLongFunction<Snapshot> measure;

    Achievement(String id, String name, String description, long target,
                ToLongFunction<Snapshot> measure) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.target = target;
        this.measure = measure;
    }

    public Progress against(Snapshot snapshot) {
        long raw = measure.applyAsLong(snapshot);
        return new Progress(id, name, description, Math.min(raw, target), target, raw >= target);
    }

    /** The whole catalogue in declaration order — earned and unearned alike. */
    public static List<Progress> evaluate(Snapshot snapshot) {
        return Arrays.stream(values()).map(a -> a.against(snapshot)).toList();
    }
}
