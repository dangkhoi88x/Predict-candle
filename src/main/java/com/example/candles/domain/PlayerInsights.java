package com.example.candles.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;

/**
 * Habits in a player's recent calls — the part of the profile that says not how often they are
 * right but where they go wrong. Pure: {@code InsightsService} loads the guesses and the candles
 * each one was made on, and this folds them.
 *
 * Nothing is stored, the same bargain as {@link PlayStreak} and {@link Achievement}: a finding is a
 * question asked of the history, so it cannot drift from it and a player's next call moves it.
 *
 * Three rules keep this from telling people things that are not true:
 * <ul>
 *   <li><b>Nothing below {@link #MIN_SAMPLE} answered calls.</b> Ten coin flips land 7–3 often
 *       enough that "you favour LONG" would be noise presented as a diagnosis. The timeout finding
 *       is the exception and counts every call, since it is the one habit that leaves too few
 *       answered ones to judge anything else.</li>
 *   <li><b>A bucket needs {@link #MIN_BUCKET} calls</b> before its accuracy is compared with
 *       anything, for the same reason one level down.</li>
 *   <li><b>A gap under {@link #GAP_POINTS} points is not a finding.</b> The profile says nothing
 *       rather than make a two-point wobble sound like a habit.</li>
 * </ul>
 *
 * A timed-out call has no direction, so it is left out of every rate here and counted on its own:
 * letting the clock run out is a habit too, but not a bias about which way charts go.
 */
public final class PlayerInsights {

    public static final int MIN_SAMPLE = 30;
    public static final int MIN_BUCKET = 10;
    public static final int GAP_POINTS = 10;
    public static final int TIMEOUT_POINTS = 15;

    /** How many of the last visible candles decide whether the chart had just risen or fallen. */
    public static final int TREND_CANDLES = 5;

    private PlayerInsights() {
    }

    /** What the chart had just done over the last {@link #TREND_CANDLES} candles before the call. */
    public enum Trend { RISING, FALLING, FLAT }

    /**
     * Local time of day, in the player's zone — a habit belongs to a person's evening, not to UTC.
     * {@code fromHour} inclusive, {@code toHour} exclusive.
     */
    public enum Session {
        NIGHT(0, 6), MORNING(6, 12), AFTERNOON(12, 18), EVENING(18, 24);

        final int fromHour;
        final int toHour;

        Session(int fromHour, int toHour) {
            this.fromHour = fromHour;
            this.toHour = toHour;
        }

        static Session at(Instant instant, ZoneId zone) {
            int hour = instant.atZone(zone).getHour();
            for (Session s : values()) {
                if (hour >= s.fromHour && hour < s.toHour) return s;
            }
            throw new IllegalStateException("hour " + hour);
        }
    }

    public enum Kind { LONG_BIAS, SHORT_BIAS, WEAK_TREND, WEAK_SESSION, TIMEOUTS }

    /**
     * One call. {@code guessed} is null for a timeout; {@code trend} is null when the candles it was
     * made on could not be read back, which leaves it out of the trend buckets and nothing else.
     */
    public record Observation(Direction guessed, Direction actual, Trend trend, Instant at) {
    }

    public record Calls(long longCalls, long shortCalls, long marketUp, long marketDown,
                        long correctLong, long correctShort, long correctWhenUp, long correctWhenDown) {

        public long answered() {
            return longCalls + shortCalls;
        }

        public long correct() {
            return correctLong + correctShort;
        }
    }

    public record Bucket(long total, long correct, long longCalls) {
    }

    /**
     * Points to a section of the summary rather than carrying its own figures, so a finding and
     * the table under it cannot disagree. {@code key} names the bucket for the two WEAK kinds.
     * {@code gapPoints} is how far off the habit is — what the list is ordered by.
     */
    public record Finding(Kind kind, String key, int gapPoints) {
    }

    public record Summary(int analysed, long timedOut, Calls calls, Map<Trend, Bucket> trends,
                          Map<Session, Bucket> sessions, List<Finding> findings) {

        public boolean enough() {
            return calls.answered() >= MIN_SAMPLE;
        }
    }

    public static Summary fold(List<Observation> observations, ZoneId zone) {
        long timedOut = 0;
        long longCalls = 0, shortCalls = 0, up = 0, down = 0;
        long correctLong = 0, correctShort = 0, correctUp = 0, correctDown = 0;
        Map<Trend, long[]> trends = new EnumMap<>(Trend.class);
        Map<Session, long[]> sessions = new EnumMap<>(Session.class);

        for (Observation o : observations) {
            if (o.guessed() == null) {
                timedOut++;
                continue;
            }
            boolean correct = o.guessed() == o.actual();
            boolean calledLong = o.guessed() == Direction.LONG;
            if (calledLong) {
                longCalls++;
                if (correct) correctLong++;
            } else {
                shortCalls++;
                if (correct) correctShort++;
            }
            if (o.actual() == Direction.LONG) {
                up++;
                if (correct) correctUp++;
            } else {
                down++;
                if (correct) correctDown++;
            }
            if (o.trend() != null) add(trends.computeIfAbsent(o.trend(), t -> new long[3]), correct, calledLong);
            add(sessions.computeIfAbsent(Session.at(o.at(), zone), s -> new long[3]), correct, calledLong);
        }

        Calls calls = new Calls(longCalls, shortCalls, up, down, correctLong, correctShort, correctUp, correctDown);
        Map<Trend, Bucket> trendBuckets = buckets(trends, Trend.class);
        Map<Session, Bucket> sessionBuckets = buckets(sessions, Session.class);
        return new Summary(observations.size(), timedOut, calls, trendBuckets, sessionBuckets,
                findings(calls, timedOut, trendBuckets, sessionBuckets));
    }

    /**
     * RISING when the net move over the last {@link #TREND_CANDLES} candles is more than one average
     * candle's full range, FALLING for the same the other way, FLAT otherwise.
     *
     * Measured against the candles' own range rather than a percentage, because a 1% hour is a
     * quiet one for SOL and a violent one for BTC, and a fixed threshold would call every SOL chart
     * a trend. Against the range, "moved further than a typical candle spans" means the same thing
     * on every pair and in every market regime.
     *
     * @param lastCandles oldest first, ending on the last candle the player could see
     * @return null when there are not enough candles to judge
     */
    public static Trend trendOf(List<Candle> lastCandles) {
        if (lastCandles.size() < TREND_CANDLES) return null;
        List<Candle> window = lastCandles.subList(lastCandles.size() - TREND_CANDLES, lastCandles.size());
        double rangeSum = 0;
        for (Candle c : window) rangeSum += c.getHigh().subtract(c.getLow()).doubleValue();
        double averageRange = rangeSum / TREND_CANDLES;
        BigDecimal net = window.getLast().getClose().subtract(window.getFirst().getOpen());
        if (averageRange <= 0) return Trend.FLAT;
        double move = net.doubleValue();
        if (move > averageRange) return Trend.RISING;
        if (move < -averageRange) return Trend.FALLING;
        return Trend.FLAT;
    }

    private static void add(long[] bucket, boolean correct, boolean calledLong) {
        bucket[0]++;
        if (correct) bucket[1]++;
        if (calledLong) bucket[2]++;
    }

    private static <K extends Enum<K>> Map<K, Bucket> buckets(Map<K, long[]> raw, Class<K> type) {
        Map<K, Bucket> out = new EnumMap<>(type);
        for (K key : type.getEnumConstants()) {
            long[] b = raw.getOrDefault(key, new long[3]);
            out.put(key, new Bucket(b[0], b[1], b[2]));
        }
        return out;
    }

    private static List<Finding> findings(Calls calls, long timedOut, Map<Trend, Bucket> trends,
                                          Map<Session, Bucket> sessions) {
        List<Finding> out = new ArrayList<>();
        long answered = calls.answered();

        /* Judged on every call, not only answered ones. A player who lets the clock run out four
           times in five has too few answered calls for any other finding — and gating this one on
           answered calls too would hide the single habit that is plainly true of them. */
        long calledOrNot = answered + timedOut;
        int timeoutShare = points(timedOut, calledOrNot);
        if (calledOrNot >= MIN_SAMPLE && timeoutShare >= TIMEOUT_POINTS) {
            out.add(new Finding(Kind.TIMEOUTS, null, timeoutShare));
        }
        if (answered < MIN_SAMPLE) return List.copyOf(out);

        int directionGap = points(calls.longCalls(), answered) - points(calls.marketUp(), answered);
        if (directionGap >= GAP_POINTS) out.add(new Finding(Kind.LONG_BIAS, null, directionGap));
        if (directionGap <= -GAP_POINTS) out.add(new Finding(Kind.SHORT_BIAS, null, -directionGap));

        int overall = points(calls.correct(), answered);
        trends.forEach((trend, b) -> weak(b, overall).ifPresent(gap -> out.add(new Finding(Kind.WEAK_TREND, trend.name(), gap))));
        sessions.forEach((session, b) -> weak(b, overall).ifPresent(gap -> out.add(new Finding(Kind.WEAK_SESSION, session.name(), gap))));

        out.sort(Comparator.comparingInt(Finding::gapPoints).reversed());
        return List.copyOf(out);
    }

    private static java.util.Optional<Integer> weak(Bucket bucket, int overallPoints) {
        if (bucket.total() < MIN_BUCKET) return java.util.Optional.empty();
        int gap = overallPoints - points(bucket.correct(), bucket.total());
        return gap >= GAP_POINTS ? java.util.Optional.of(gap) : java.util.Optional.empty();
    }

    /** A share as whole percentage points, rounded half up — the unit every threshold here is in. */
    static int points(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }
}
