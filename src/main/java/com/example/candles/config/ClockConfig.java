package com.example.candles.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock} rather than every clock-reading class calling
 * {@code Instant.now()} directly.
 *
 * The gap that made this necessary: {@code LiveRoundFlowTest} called {@code Instant.now()} to
 * work out which live round to test against, and so does {@link com.example.candles.service.LiveRoundService}
 * at request time — the same wall clock, read twice, independently. That is fine except in the
 * roughly 8 minutes of every hour a round is locked ({@code candles.live.lock-before}): a build
 * that happened to run then found the round it was testing against already locked, and a test
 * expecting 200 got 400. It reached production this way — CI ran at :55 past the hour and every
 * repo push, local run and manual check before that had simply never landed in that window.
 *
 * Injecting the clock lets a test pin it to an instant known to be safely inside a round's open
 * window, so the test's outcome no longer depends on when in the hour it happens to run.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
