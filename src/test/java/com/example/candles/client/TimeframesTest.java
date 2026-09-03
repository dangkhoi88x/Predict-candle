package com.example.candles.client;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeframesTest {

    @Test
    void parsesTheUnitsTheConfigUses() {
        assertEquals(Duration.ofMinutes(15), Timeframes.parse("15m"));
        assertEquals(Duration.ofHours(1), Timeframes.parse("1h"));
        assertEquals(Duration.ofHours(4), Timeframes.parse("4h"));
        assertEquals(Duration.ofDays(1), Timeframes.parse("1d"));
    }

    @Test
    void rejectsAnUnknownUnit() {
        assertThrows(IllegalArgumentException.class, () -> Timeframes.parse("1w"));
    }

    @Test
    void currentPeriodStartRoundsDownToTheOpenCandle() {
        Instant midHour = Instant.parse("2026-08-30T04:37:12Z");
        assertEquals(Instant.parse("2026-08-30T04:00:00Z"), Timeframes.currentPeriodStart(midHour, "1h"));
        assertEquals(Instant.parse("2026-08-30T04:30:00Z"), Timeframes.currentPeriodStart(midHour, "15m"));
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), Timeframes.currentPeriodStart(midHour, "1d"));
    }

    @Test
    void anInstantExactlyOnTheBoundaryOpensThatPeriod() {
        // The 05:00 candle has just opened and is the one that must not be stored yet.
        Instant onTheHour = Instant.parse("2026-08-30T05:00:00Z");
        assertEquals(onTheHour, Timeframes.currentPeriodStart(onTheHour, "1h"));
    }

    @Test
    void syncEndBoundStopsOneMillisecondBeforeTheOpenCandle() {
        Instant now = Instant.parse("2026-08-30T04:05:00Z");
        Instant to = Timeframes.currentPeriodStart(now, "1h").minusMillis(1);
        // 03:00 is the newest closed candle; 04:00 is still forming.
        assertEquals(Instant.parse("2026-08-30T03:59:59.999Z"), to);
    }
}
