package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.candles.dto.response.AdminStats;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

/**
 * Builds the overview's time series.
 *
 * Everything is bucketed in UTC, matching {@code OpsService}'s idea of "today" — an admin
 * page that disagreed with the operations panel about which day a guess landed on would be
 * worse than one that is an hour off from local midnight.
 *
 * The result is cached for a minute. This is the one admin read that scans guess history, and
 * it sits on a page people leave open and hit refresh on; a minute is short enough that the
 * numbers still feel live and long enough that the refresh button costs nothing.
 */
@Service
public class AdminStatsService {

    /** Which calendar unit each range groups by, and how many buckets it shows. */
    public enum Range {
        WEEK("day", 7),
        MONTH("month", 12),
        YEAR("year", 5);

        private final String unit;
        private final int buckets;

        Range(String unit, int buckets) {
            this.unit = unit;
            this.buckets = buckets;
        }

        static Range parse(String value) {
            if (value == null) return MONTH;
            return switch (value.toLowerCase()) {
                case "week" -> WEEK;
                case "year" -> YEAR;
                default -> MONTH;
            };
        }
    }

    private static final int DAILY_BUCKETS = 14;
    private static final int WEEKLY_BUCKETS = 12;

    private final GuessResultRepository guessResults;
    private final UserRepository users;
    private final Cache<Range, AdminStats> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public AdminStatsService(GuessResultRepository guessResults, UserRepository users) {
        this.guessResults = guessResults;
        this.users = users;
    }

    /* The transaction sits here rather than on build(): a cache loader is called on this
       instance directly, so an annotation down there would never reach the proxy. */
    @Transactional(readOnly = true)
    public AdminStats stats(String range) {
        return stats(range, false);
    }

    /**
     * {@code fresh} is the refresh button. Without it the button is a partial lie: the pane's
     * timestamp and its four KPI figures come from the operations snapshot and update, while
     * the three charts quietly return the same cached minute.
     */
    @Transactional(readOnly = true)
    public AdminStats stats(String range, boolean fresh) {
        Range parsed = Range.parse(range);
        if (fresh) {
            cache.invalidate(parsed);
        }
        return cache.get(parsed, this::build);
    }

    /** Drops the cached series, so the next read goes back to the database. */
    public void evict() {
        cache.invalidateAll();
    }

    private AdminStats build(Range range) {
        Instant now = Instant.now();
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);

        List<AdminStats.Bucket> main = series(range.unit, range.buckets, utcNow);
        /* WEEK's main series already is the daily one, only shorter. Reusing it would save a
           query but leave the player panel drawing seven bars on one range and fourteen on
           the others, which is a different chart wearing the same label. */
        List<AdminStats.Bucket> daily = series("day", DAILY_BUCKETS, utcNow);
        List<AdminStats.Bucket> weekly = series("week", WEEKLY_BUCKETS, utcNow);
        List<AdminStats.AccountPoint> accounts = accountCurve(utcNow);

        return new AdminStats(range.name().toLowerCase(), now, main, daily, weekly, accounts,
                totals(weekly, accounts, now), deltas(accounts, now));
    }

    /**
     * {@code count} buckets ending with the one {@code now} falls in, oldest first, with the
     * empty ones present and zeroed — a gap in the data is a column of zero height, not a
     * column the chart silently leaves out.
     */
    private List<AdminStats.Bucket> series(String unit, int count, ZonedDateTime now) {
        List<Instant> starts = new ArrayList<>(count);
        ZonedDateTime last = truncate(now, unit);
        for (int i = count - 1; i >= 0; i--) {
            starts.add(minus(last, unit, i).toInstant());
        }

        Instant since = starts.get(0);
        Instant until = next(last, unit).toInstant();

        Map<Instant, Object[]> rows = new HashMap<>();
        for (Object[] row : guessResults.bucketed(unit, since, until)) {
            rows.put(instant(row[0]), row);
        }

        List<AdminStats.Bucket> buckets = new ArrayList<>(count);
        for (Instant start : starts) {
            Object[] row = rows.get(start);
            long longCount = row == null ? 0 : asLong(row[2]);
            long shortCount = row == null ? 0 : asLong(row[3]);
            buckets.add(new AdminStats.Bucket(start,
                    row == null ? 0 : asLong(row[1]),
                    longCount, shortCount, longCount + shortCount,
                    row == null ? 0 : asLong(row[4]),
                    row == null ? 0 : asLong(row[5])));
        }
        return buckets;
    }

    /**
     * The account total at the end of each of the last fourteen days.
     *
     * Built by walking today's count backwards through the daily signups rather than by
     * counting rows fourteen times: one grouped query and some subtraction say the same thing
     * and touch the table once.
     */
    private List<AdminStats.AccountPoint> accountCurve(ZonedDateTime now) {
        ZonedDateTime today = truncate(now, "day");
        Instant since = today.minusDays(DAILY_BUCKETS - 1L).toInstant();
        Instant until = next(today, "day").toInstant();

        Map<Instant, Long> added = new HashMap<>();
        for (Object[] row : users.signupsByDay(since, until)) {
            added.put(instant(row[0]), asLong(row[1]));
        }

        long running = users.count();
        AdminStats.AccountPoint[] points = new AdminStats.AccountPoint[DAILY_BUCKETS];
        for (int i = DAILY_BUCKETS - 1; i >= 0; i--) {
            Instant start = today.minusDays(DAILY_BUCKETS - 1L - i).toInstant();
            long arrived = added.getOrDefault(start, 0L);
            points[i] = new AdminStats.AccountPoint(start, arrived, running);
            running -= arrived;
        }
        return List.of(points);
    }

    /**
     * Summed from the weekly buckets already in hand rather than queried again. That is not
     * only cheaper — it is the only way the headline percentage and the line drawn under it
     * can be the same measurement over the same window.
     */
    private AdminStats.Totals totals(List<AdminStats.Bucket> weekly,
                                     List<AdminStats.AccountPoint> accounts, Instant now) {
        long guesses = weekly.stream().mapToLong(AdminStats.Bucket::guesses).sum();
        long correct = weekly.stream().mapToLong(AdminStats.Bucket::correct).sum();
        Instant midnight = now.truncatedTo(ChronoUnit.DAYS);
        return new AdminStats.Totals(guesses,
                guesses == 0 ? null : (double) correct / guesses,
                guessResults.activePlayersBetween(midnight, now),
                accounts.getLast().total());
    }

    private AdminStats.Deltas deltas(List<AdminStats.AccountPoint> accounts, Instant now) {
        Instant midnight = now.truncatedTo(ChronoUnit.DAYS);
        Instant yesterday = midnight.minus(1, ChronoUnit.DAYS);
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        Instant twoWeeksAgo = now.minus(14, ChronoUnit.DAYS);

        Object[] today = unwrap(guessResults.activityBetween(midnight, now));
        Object[] priorDay = unwrap(guessResults.activityBetween(yesterday, midnight));
        Object[] week = unwrap(guessResults.activityBetween(weekAgo, now));
        Object[] priorWeek = unwrap(guessResults.activityBetween(twoWeeksAgo, weekAgo));

        /* The account KPI shows a running total, so its delta is that total's growth over the
           last seven days — read straight off the curve the sparkline draws. Comparing weekly
           active players here, as this once did, put three unrelated quantities on one card. */
        long accountsNow = accounts.getLast().total();
        long accountsWeekAgo = accountsNow - accounts.subList(
                Math.max(accounts.size() - 7, 0), accounts.size())
                .stream().mapToLong(AdminStats.AccountPoint::added).sum();

        return new AdminStats.Deltas(
                change(asLong(today[0]), asLong(priorDay[0])),
                change(accuracy(week), accuracy(priorWeek)),
                change(accountsNow, accountsWeekAgo),
                change(asLong(week[0]), asLong(priorWeek[0])));
    }

    private static Double accuracy(Object[] activity) {
        long answered = asLong(activity[0]);
        return answered == 0 ? null : (double) asLong(activity[1]) / answered;
    }

    private static Double change(Number current, Number previous) {
        if (previous == null || current == null || previous.doubleValue() == 0) return null;
        return (current.doubleValue() - previous.doubleValue()) / previous.doubleValue();
    }

    /* ---- calendar arithmetic, all in UTC ---- */

    private static ZonedDateTime truncate(ZonedDateTime at, String unit) {
        return switch (unit) {
            case "day" -> at.truncatedTo(ChronoUnit.DAYS);
            // Postgres date_trunc('week', …) starts weeks on Monday; DayOfWeek does too.
            case "week" -> at.truncatedTo(ChronoUnit.DAYS).minusDays(at.getDayOfWeek().getValue() - 1L);
            case "month" -> at.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            default -> at.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        };
    }

    private static ZonedDateTime minus(ZonedDateTime at, String unit, int amount) {
        return switch (unit) {
            case "day" -> at.minusDays(amount);
            case "week" -> at.minusWeeks(amount);
            case "month" -> at.minusMonths(amount);
            default -> at.minusYears(amount);
        };
    }

    private static ZonedDateTime next(ZonedDateTime at, String unit) {
        return minus(at, unit, -1);
    }

    /**
     * The bucket column comes back as a zoneless timestamp, because the query truncates
     * {@code created_at at time zone 'UTC'}. Reading it as anything but UTC would shift every
     * bucket by the server's offset.
     */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant already) {
            return already;
        }
        throw new IllegalStateException("Unexpected bucket type: " + value.getClass());
    }

    /** Mirrors OpsService: some providers wrap a single aggregate row in another array. */
    private static Object[] unwrap(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
