package com.example.candles.service;

import com.example.candles.dto.response.OpsSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentErrorsTest {

    private static final Instant T0 = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    void theSameFailureAgainFoldsIntoOneRowWithACount() {
        MovableClock clock = new MovableClock(T0);
        RecentErrors errors = new RecentErrors(clock);

        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        clock.advance(Duration.ofSeconds(4));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        clock.advance(Duration.ofSeconds(4));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");

        List<OpsSnapshot.RecentError> listed = errors.snapshot();
        assertEquals(1, listed.size());
        assertEquals(3, listed.getFirst().count());
        assertEquals(T0, listed.getFirst().firstAt());
        assertEquals(T0.plusSeconds(8), listed.getFirst().lastAt());
    }

    @Test
    void aDifferentFailureInBetweenStartsANewRowNewestFirst() {
        RecentErrors errors = new RecentErrors(new MovableClock(T0));

        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        errors.record("sync", "BTCUSDT", "java.net.UnknownHostException: api.binance.com");
        errors.record("upstream", "GET /api/live/round", "HTTP 418");

        List<OpsSnapshot.RecentError> listed = errors.snapshot();
        assertEquals(List.of("upstream", "sync", "upstream"), listed.stream().map(OpsSnapshot.RecentError::source).toList());
        assertTrue(listed.stream().allMatch(e -> e.count() == 1));
    }

    @Test
    void onlyTheNewestFiftyAreKeptAndLongSummariesAreCutToOneLine() {
        RecentErrors errors = new RecentErrors(new MovableClock(T0));
        for (int i = 0; i < RecentErrors.CAPACITY + 10; i++) {
            errors.record("server", "GET /x/" + i, "boom " + i);
        }
        errors.record("server", "GET /long", "line one\n\tline two " + "x".repeat(500));

        List<OpsSnapshot.RecentError> listed = errors.snapshot();
        assertEquals(RecentErrors.CAPACITY, listed.size());
        assertEquals("GET /long", listed.getFirst().where());
        assertEquals("GET /x/59", listed.get(1).where());
        String summary = listed.getFirst().summary();
        assertTrue(summary.startsWith("line one line two "));
        assertEquals(RecentErrors.MAX_SUMMARY, summary.length());
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
