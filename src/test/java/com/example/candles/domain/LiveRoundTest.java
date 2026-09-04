package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live round is computed from the clock alone — no row anywhere says which round is
 * running — so these pin exactly the arithmetic that guarantee is built on.
 */
class LiveRoundTest {

    private static final Duration LOCK = Duration.ofMinutes(8);

    @Test
    void aRoundOpensOnTheHourAndClosesOnTheNext() {
        Instant midHour = Instant.parse("2026-09-03T14:37:12Z");
        LiveRound round = LiveRound.at(midHour, "1h", LOCK);

        assertThat(round.openTime()).isEqualTo(Instant.parse("2026-09-03T14:00:00Z"));
        assertThat(round.closeAt()).isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
        assertThat(round.lockAt()).isEqualTo(Instant.parse("2026-09-03T14:52:00Z"));
    }

    @Test
    void lockedFromTheLockInstantOnwardsInclusive() {
        LiveRound round = LiveRound.at(Instant.parse("2026-09-03T14:00:00Z"), "1h", LOCK);

        assertThat(round.isLocked(round.lockAt().minusMillis(1))).isFalse();
        assertThat(round.isLocked(round.lockAt())).isTrue();
        assertThat(round.isLocked(round.lockAt().plusMillis(1))).isTrue();
    }

    @Test
    void closedFromTheCloseInstantOnwardsInclusive() {
        LiveRound round = LiveRound.at(Instant.parse("2026-09-03T14:00:00Z"), "1h", LOCK);

        assertThat(round.hasClosed(round.closeAt().minusMillis(1))).isFalse();
        assertThat(round.hasClosed(round.closeAt())).isTrue();
    }

    /**
     * The only thing that has to hold for numbering to mean anything: the same instant always
     * names the same round, whether it is "the round happening now" or "the round this
     * historical candle belongs to" — LiveRoundService uses both call shapes.
     */
    @Test
    void theSameOpenTimeAlwaysProducesTheSameRoundNumber() {
        Instant open = Instant.parse("2026-09-03T14:00:00Z");
        LiveRound asCurrent = LiveRound.at(open, "1h", LOCK);
        LiveRound asHistory = LiveRound.at(open.plusSeconds(1799), "1h", LOCK);

        assertThat(asHistory.number()).isEqualTo(asCurrent.number());
        assertThat(asHistory.openTime()).isEqualTo(asCurrent.openTime());
    }

    @Test
    void consecutiveHoursGetConsecutiveNumbers() {
        Instant hour = Instant.parse("2026-09-03T14:00:00Z");
        long first = LiveRound.at(hour, "1h", LOCK).number();
        long next = LiveRound.at(hour.plus(Duration.ofHours(1)), "1h", LOCK).number();

        assertThat(next).isEqualTo(first + 1);
    }

    /**
     * The direction the history popup needs: given a round number a player clicked on, get back
     * to the same openTime/lockAt/closeAt {@link #at} would have produced while that round was
     * live. If this drifts, "round 19" in the URL and "round 19" on screen stop being the same
     * round.
     */
    @Test
    void byNumberInvertsAtForAnyRoundEverPlayed() {
        Instant now = Instant.parse("2026-09-03T14:37:12Z");
        LiveRound current = LiveRound.at(now, "1h", LOCK);

        for (int stepsBack = 0; stepsBack < 50; stepsBack++) {
            Instant probe = now.minus(Duration.ofHours(stepsBack));
            LiveRound expected = LiveRound.at(probe, "1h", LOCK);
            LiveRound byNumber = LiveRound.byNumber(expected.number(), "1h", LOCK);

            assertThat(byNumber).isEqualTo(expected);
        }
        assertThat(LiveRound.byNumber(current.number(), "1h", LOCK)).isEqualTo(current);
    }

    @Test
    void previousStepsBackExactlyOnePeriod() {
        LiveRound round = LiveRound.at(Instant.parse("2026-09-03T14:22:00Z"), "1h", LOCK);
        LiveRound before = round.previous("1h", LOCK);

        assertThat(before.openTime()).isEqualTo(round.openTime().minus(Duration.ofHours(1)));
        assertThat(before.number()).isEqualTo(round.number() - 1);
    }

    /** A lock window as long as (or longer than) the round would gate from the first second. */
    @Test
    void aLockWindowNotShorterThanTheRoundIsClampedRatherThanGatingFromTheStart() {
        LiveRound round = LiveRound.at(Instant.parse("2026-09-03T14:00:00Z"), "1h", Duration.ofHours(1));

        assertThat(round.lockAt()).isEqualTo(round.openTime().plus(Duration.ofMinutes(30)));
    }

    @Test
    void millisUntilLockAndCloseCountDownToZeroNotNegative() {
        LiveRound round = LiveRound.at(Instant.parse("2026-09-03T14:00:00Z"), "1h", LOCK);

        assertThat(round.millisUntilLock(round.lockAt().plusSeconds(5))).isZero();
        assertThat(round.millisUntilClose(round.closeAt().plusSeconds(5))).isZero();
        assertThat(round.millisUntilLock(round.openTime())).isEqualTo(Duration.ofMinutes(52).toMillis());
    }
}
