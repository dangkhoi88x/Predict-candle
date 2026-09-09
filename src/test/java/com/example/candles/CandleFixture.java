package com.example.candles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.CandleRepository;

/**
 * Hourly candle history for tests that need a chart to exist before they can assert anything.
 *
 * <h2>Why this exists</h2>
 *
 * Three test classes used to read whatever the first-run Binance backfill had left in the
 * database. That passes on a developer machine, where the app has been run and every pair has
 * roughly forty thousand candles, and fails on CI, where the database starts empty and the
 * runner cannot reach Binance at all — it answers every request with HTTP 451, "Service
 * unavailable from a restricted location". So the suite was quietly asserting that somebody had
 * already run the app on this machine, and had been red on {@code main} for eight merges.
 *
 * {@code PracticeRoundFlowTest} had already learned this lesson and seeds its own pair; this is
 * that idea made shared, because three more classes needed it and a fourth copy of the loop
 * would have been the point where they started to drift apart.
 *
 * <h2>Seeded only when the asset has none</h2>
 *
 * {@link #seedIfEmpty} is a no-op against a pair that already has history. That keeps a
 * developer machine testing against real Binance candles — the thing the pattern matchers were
 * actually tuned on — while CI gets a corpus that behaves enough like a market to stand in for
 * one. It also means the fixture can never collide with the unique constraint on (asset,
 * timeframe, open_time).
 *
 * <h2>Why a random walk rather than a fixed shape</h2>
 *
 * {@code PatternQuizService} needs a history in which at least one of the thirteen candlestick
 * patterns occurs <em>unambiguously</em> — matched by exactly one entry in the library. A
 * hand-written series of alternating up and down candles has no dojis, no hammers and no
 * engulfings in it, so the quiz would find nothing to ask about. A walk with varied bodies and
 * wicks produces all of them the way a real chart does.
 *
 * The walk is seeded from a constant, so it is the same series on every run and on every
 * machine: a test that fails one time in twenty because the dice came up flat is worse than no
 * test. Change {@link #SEED} and you are choosing a different market — expect the quiz's
 * questions to move with it.
 */
public final class CandleFixture {

    private CandleFixture() {
    }

    private static final long SEED = 20260309L;

    /**
     * The whole corpus closes before this instant, so a test asking for "the history before day
     * X" gets all of it for any X the suite uses. {@code PatternQuizTest} walks March 2026 and
     * {@code DailyRoundSelectionTest} draws on 2026-03-15; both sit after this.
     */
    public static final Instant END_EXCLUSIVE =
            LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    /**
     * Enough hours to give the pattern library room to turn up clean occurrences, and enough
     * for a 400-bar hourly chart folded to 120 four-hour bars.
     */
    public static final int DEFAULT_COUNT = 1_200;

    /**
     * Fills {@code asset} with {@link #DEFAULT_COUNT} hourly candles ending just before
     * {@link #END_EXCLUSIVE}, unless it already has some.
     *
     * @return the number of candles written — zero when the pair already had history
     */
    public static int seedIfEmpty(CandleRepository candles, Asset asset, String timeframe) {
        if (candles.countByAssetAndTimeframe(asset, timeframe) > 0) {
            return 0;
        }
        List<Candle> series = generate(asset, timeframe, DEFAULT_COUNT);
        candles.saveAllAndFlush(series);
        return series.size();
    }

    /**
     * A random walk in the shape a market makes: a drifting close, a body that is usually small
     * relative to the range and occasionally most of it, and wicks on both sides.
     *
     * The numbers are what matter here, so they are worth stating. Bodies run from about 0% to
     * 1.2% of price and wicks to about 0.6%, which is roughly hourly crypto. The 0% end is not
     * an accident: a body that rounds to nothing is a doji, and without those in the corpus the
     * quiz's library is missing an entry it is expected to be able to ask about.
     */
    private static List<Candle> generate(Asset asset, String timeframe, int count) {
        /* Per pair, so four seeded assets are four different markets rather than one market
           four times. The daily draw picks an (asset, window) pair and a month of them is
           asserted to be mostly distinct — identical series would make that assertion pass on
           the asset name alone. */
        Random random = new Random(SEED + asset.getSymbol().hashCode());
        Instant start = END_EXCLUSIVE.minus(count, ChronoUnit.HOURS);

        List<Candle> out = new ArrayList<>(count);
        double price = 30_000;

        for (int i = 0; i < count; i++) {
            double open = price;
            // Signed body, so direction is decided by the same draw that decides size.
            double body = open * (random.nextDouble() * 0.012) * (random.nextBoolean() ? 1 : -1);
            double close = open + body;

            double top = Math.max(open, close);
            double bottom = Math.min(open, close);
            double high = top + open * random.nextDouble() * 0.006;
            double low = bottom - open * random.nextDouble() * 0.006;

            out.add(new Candle(asset, timeframe, start.plus(i, ChronoUnit.HOURS),
                    money(open), money(high), money(low), money(close),
                    // Varied, because a folded bar's volume is asserted to be the sum of its
                    // hours and identical volumes would let a wrong fold pass.
                    BigDecimal.valueOf(500 + random.nextInt(1_500))));
            price = close;
        }
        return out;
    }

    /** The column is numeric(20, 8); rounding here keeps a written candle equal to a read one. */
    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
