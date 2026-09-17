package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

import com.example.candles.repository.AppErrorRepository;

/**
 * Keeps {@code app_errors} bounded, by age and by row count.
 *
 * Both limits are needed and they bound different failures: age is what makes the table a picture
 * of the last fortnight rather than of the project's whole history, and the row cap is what stops
 * one bad night — an exchange ban raising a different error on every poll — from filling it inside
 * that window. A log nobody deletes is a table that only grows, on a free database with a quota.
 */
@Component
public class ErrorRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ErrorRetentionScheduler.class);

    private final AppErrorRepository errors;
    private final Clock clock;
    private final Duration retention;
    private final int maxRows;

    public ErrorRetentionScheduler(AppErrorRepository errors, Clock clock,
                                   @Value("${candles.errors.retention:P14D}") Duration retention,
                                   @Value("${candles.errors.max-rows:2000}") int maxRows) {
        this.errors = errors;
        this.clock = clock;
        this.retention = retention;
        this.maxRows = maxRows;
    }

    /** Nightly, well away from the hourly candle sync. */
    @Scheduled(cron = "${candles.errors.trim-cron:0 40 3 * * *}")
    public void trim() {
        int removed = purge(retention, maxRows);
        if (removed > 0) log.info("Trimmed {} old rows from app_errors", removed);
    }

    /**
     * Returns how many rows went, so the scheduled run can say so and a test can assert it. The
     * limits are arguments rather than fields so a test can use tiny ones through this same bean —
     * a hand-built copy would carry no transaction, and both deletes need one.
     */
    @Transactional
    public int purge(Duration olderThan, int keep) {
        int byAge = errors.deleteByLastAtBefore(clock.instant().minus(olderThan));
        return byAge + errors.trimTo(keep);
    }
}
