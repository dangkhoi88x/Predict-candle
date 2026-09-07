package com.example.candles.domain;

/**
 * How much help the chart is giving, as a function of how many guesses the player has missed on
 * it so far.
 *
 * Tied to misses rather than to how far through the chart they are, which is the whole point:
 * a player reading the chart correctly is not handed anything they did not ask for, and one who
 * is lost gets more to work with each time. Songless does the same thing by lengthening the clip
 * on every miss — the round gets easier only for the person who needs it easier.
 *
 * A player can of course miss on purpose to unlock these. That costs them the guess, the streak
 * bonus and the point, which is a real price rather than a loophole — the same trade Songless
 * makes with its skip button.
 *
 * The order is deliberate, cheapest signal first. Volume is data the chart was withholding.
 * The moving average is only a smoothing of candles already on screen, so it adds no facts —
 * it makes the trend easier to read, which is exactly what a struggling player is missing.
 * The pattern name is the strongest, and it still names something in the candles already shown
 * rather than anything about the answer.
 */
public record HintLevel(boolean volume, boolean movingAverage, boolean pattern) {

    private static final int VOLUME_AT = 1;
    private static final int MOVING_AVERAGE_AT = 2;
    private static final int PATTERN_AT = 3;

    public static HintLevel forMisses(int misses) {
        return new HintLevel(misses >= VOLUME_AT, misses >= MOVING_AVERAGE_AT, misses >= PATTERN_AT);
    }

    public boolean any() {
        return volume || movingAverage || pattern;
    }
}
