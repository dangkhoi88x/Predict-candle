package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HintLevelTest {

    @Test
    void aPlayerWhoHasMissedNothingIsGivenNothing() {
        HintLevel level = HintLevel.forMisses(0);

        assertThat(level.any()).isFalse();
        assertThat(level.volume()).isFalse();
        assertThat(level.movingAverage()).isFalse();
        assertThat(level.pattern()).isFalse();
    }

    @Test
    void hintsArriveOneAtATimeAndAccumulate() {
        assertThat(HintLevel.forMisses(1)).isEqualTo(new HintLevel(true, false, false));
        assertThat(HintLevel.forMisses(2)).isEqualTo(new HintLevel(true, true, false));
        assertThat(HintLevel.forMisses(3)).isEqualTo(new HintLevel(true, true, true));
    }

    @Test
    void missingMoreThanThereAreHintsJustKeepsThemAllOn() {
        // Charts run to more guesses than there are hints, so the top of the ladder has to be
        // a resting place rather than something that falls off the end.
        assertThat(HintLevel.forMisses(9)).isEqualTo(HintLevel.forMisses(3));
    }
}
