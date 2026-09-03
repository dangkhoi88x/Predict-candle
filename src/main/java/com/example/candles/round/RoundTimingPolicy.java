package com.example.candles.round;

import com.example.candles.config.CandlesProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a guess arrived within the shot clock, measured from when the server minted
 * the round token rather than from anything the client says about elapsed time.
 *
 * Two edges, for two different problems. Too slow is the game rule G6 puts on screen: the
 * countdown is only a real constraint if the server enforces it, otherwise a player can pause
 * it by opening another tab and take as long as they like. Too fast is the automation check —
 * a chart cannot be read and a button pressed inside a quarter of a second.
 */
@Component
public class RoundTimingPolicy {

    private final CandlesProperties properties;

    public RoundTimingPolicy(CandlesProperties properties) {
        this.properties = properties;
    }

    /** The countdown the client shows, in seconds. */
    public int guessSeconds() {
        return properties.round().timing().seconds();
    }

    public void check(Instant tokenIssuedAt, boolean answered) {
        CandlesProperties.Timing timing = properties.round().timing();
        Duration elapsed = Duration.between(tokenIssuedAt, Instant.now());

        if (elapsed.compareTo(timing.minThinkTime()) < 0) {
            throw new GuessOutOfTimeException("Câu trả lời đến quá nhanh để là của một người chơi.");
        }

        /*
         * A timeout is the client admitting the clock ran out, so it is exempt from the
         * deadline it is reporting — rejecting it would leave the round stuck with no way
         * forward. It still has to arrive inside the token's own TTL, which the signature
         * check upstream already enforces.
         */
        if (!answered) {
            return;
        }

        Duration limit = Duration.ofSeconds(timing.seconds()).plus(timing.grace());
        if (elapsed.compareTo(limit) > 0) {
            throw new GuessOutOfTimeException("Đã hết giờ cho nến này.");
        }
    }
}
