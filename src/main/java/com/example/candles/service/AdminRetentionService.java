package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.dto.response.AdminRetention;
import com.example.candles.repository.LivePredictionRepository;

/**
 * Whether players come back, measured off the timestamps already on their calls — the same
 * approach {@code PlayStreak} takes, and for the same reason: an events table recording that
 * someone played would be a second copy of {@code guess_results}, free to drift from it.
 *
 * This exists to be read twice: once now, and once after the daily challenge ships. A number
 * that only ever gets looked at after the change cannot say whether the change did anything.
 *
 * Cached for a minute, like {@code AdminStatsService} and for the same reason — it scans the
 * guess table, and it sits on a page with a refresh button.
 */
@Service
public class AdminRetentionService {

    /** Long enough that a weekly rhythm is visible, short enough to stay one screen. */
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    private static final int WEEK = 7;

    private final LivePredictionRepository plays;
    private final Clock clock;
    private final Cache<Integer, AdminRetention> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public AdminRetentionService(LivePredictionRepository plays, Clock clock) {
        this.plays = plays;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminRetention retention(Integer days, boolean fresh) {
        int window = Math.clamp(days == null ? DEFAULT_DAYS : days, 1, MAX_DAYS);
        if (fresh) {
            cache.invalidate(window);
        }
        return cache.get(window, this::compute);
    }

    private AdminRetention compute(int days) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate since = today.minusDays(days - 1L);

        List<AdminRetention.Cohort> cohorts = new ArrayList<>();
        long newPlayers = 0;
        long nextDayEligible = 0;
        long returnedNextDay = 0;
        long withinWeekEligible = 0;
        long returnedWithinWeek = 0;

        for (Object[] row : plays.retentionCohorts(since)) {
            LocalDate day = (LocalDate) row[0];
            long cohortSize = ((Number) row[1]).longValue();
            long nextDay = ((Number) row[2]).longValue();
            long withinWeek = ((Number) row[3]).longValue();

            // A cohort has had its chance at coming back the next day only once that day is
            // over, and at coming back within the week only once the week is. Immature cohorts
            // stay in the list — they are real players who joined — but out of the fractions.
            boolean nextDayMature = !day.isAfter(today.minusDays(1));
            boolean withinWeekMature = !day.isAfter(today.minusDays(WEEK));

            newPlayers += cohortSize;
            if (nextDayMature) {
                nextDayEligible += cohortSize;
                returnedNextDay += nextDay;
            }
            if (withinWeekMature) {
                withinWeekEligible += cohortSize;
                returnedWithinWeek += withinWeek;
            }

            cohorts.add(new AdminRetention.Cohort(
                    day, cohortSize, nextDay, withinWeek, nextDayMature, withinWeekMature));
        }

        List<AdminRetention.Daily> daily = new ArrayList<>();
        long totalPlays = 0;
        for (Object[] row : plays.dailyActivitySince(since.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            long dayPlays = ((Number) row[1]).longValue();
            totalPlays += dayPlays;
            daily.add(new AdminRetention.Daily(
                    (LocalDate) row[0], dayPlays, ((Number) row[2]).longValue()));
        }

        // Player-days, not players: someone active on three days counts three times, which is
        // exactly what makes `plays / activePlayerDays` mean "calls per player on a day they
        // played". A distinct count over the window would divide a month of calls by the people
        // who made them and quietly answer a different question.
        long activePlayerDays = daily.stream().mapToLong(AdminRetention.Daily::activePlayers).sum();

        return new AdminRetention(since, today,
                new AdminRetention.Summary(newPlayers, nextDayEligible, returnedNextDay,
                        withinWeekEligible, returnedWithinWeek, totalPlays, activePlayerDays),
                List.copyOf(cohorts), List.copyOf(daily));
    }
}
