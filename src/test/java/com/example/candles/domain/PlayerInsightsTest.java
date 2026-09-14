package com.example.candles.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;

import static com.example.candles.domain.PlayerInsights.Kind.LONG_BIAS;
import static com.example.candles.domain.PlayerInsights.Kind.SHORT_BIAS;
import static com.example.candles.domain.PlayerInsights.Kind.TIMEOUTS;
import static com.example.candles.domain.PlayerInsights.Kind.WEAK_SESSION;
import static com.example.candles.domain.PlayerInsights.Kind.WEAK_TREND;
import static com.example.candles.entity.Direction.LONG;
import static com.example.candles.entity.Direction.SHORT;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerInsightsTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** 03:00 UTC is 10:00 in Vietnam — morning. */
    private static final Instant MORNING = Instant.parse("2026-09-14T03:00:00Z");
    /** 13:00 UTC is 20:00 in Vietnam — evening. */
    private static final Instant EVENING = Instant.parse("2026-09-14T13:00:00Z");

    private static PlayerInsights.Observation call(Direction guessed, Direction actual, PlayerInsights.Trend trend, Instant at) {
        return new PlayerInsights.Observation(guessed, actual, trend, at);
    }

    private static void repeat(List<PlayerInsights.Observation> into, int times, PlayerInsights.Observation o) {
        for (int i = 0; i < times; i++) into.add(o);
    }

    @Test
    void callsAreCountedBothByWhatWasCalledAndByWhatHappened() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        repeat(obs, 3, call(LONG, LONG, null, MORNING));   // called long, right
        repeat(obs, 2, call(LONG, SHORT, null, MORNING));  // called long, wrong
        repeat(obs, 1, call(SHORT, SHORT, null, MORNING)); // called short, right
        repeat(obs, 4, call(null, LONG, null, MORNING));   // timed out

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        assertThat(s.analysed()).isEqualTo(10);
        assertThat(s.timedOut()).isEqualTo(4);
        PlayerInsights.Calls c = s.calls();
        assertThat(c.answered()).isEqualTo(6);
        assertThat(c.longCalls()).isEqualTo(5);
        assertThat(c.shortCalls()).isEqualTo(1);
        assertThat(c.marketUp()).isEqualTo(3);
        assertThat(c.marketDown()).isEqualTo(3);
        assertThat(c.correctLong()).isEqualTo(3);
        assertThat(c.correctShort()).isEqualTo(1);
        assertThat(c.correctWhenUp()).isEqualTo(3);
        assertThat(c.correctWhenDown()).isEqualTo(1);
    }

    @Test
    void belowTheMinimumSampleNothingIsConcludedHoweverLopsided() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // 29 answered calls, every one LONG on a falling market — as biased as it gets, and still
        // too few to say so.
        repeat(obs, PlayerInsights.MIN_SAMPLE - 1, call(LONG, SHORT, PlayerInsights.Trend.FALLING, MORNING));

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        assertThat(s.enough()).isFalse();
        assertThat(s.findings()).isEmpty();
    }

    @Test
    void callingLongFarMoreOftenThanMarketsRiseIsALongBias() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // 40 calls: 30 LONG, 10 SHORT; the market rose in 20. 75% vs 50%.
        repeat(obs, 15, call(LONG, LONG, null, MORNING));
        repeat(obs, 15, call(LONG, SHORT, null, MORNING));
        repeat(obs, 5, call(SHORT, LONG, null, MORNING));
        repeat(obs, 5, call(SHORT, SHORT, null, MORNING));

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        assertThat(s.findings()).extracting(PlayerInsights.Finding::kind).contains(LONG_BIAS).doesNotContain(SHORT_BIAS);
        assertThat(s.findings()).filteredOn(f -> f.kind() == LONG_BIAS).first()
                .extracting(PlayerInsights.Finding::gapPoints).isEqualTo(25);
    }

    @Test
    void aNinePointGapIsNotAFinding() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // 100 calls: 59 LONG against 50 rises — nine points, just under the line.
        repeat(obs, 50, call(LONG, LONG, null, MORNING));
        repeat(obs, 9, call(LONG, SHORT, null, MORNING));
        repeat(obs, 41, call(SHORT, SHORT, null, MORNING));

        assertThat(PlayerInsights.fold(obs, VN).findings()).extracting(PlayerInsights.Finding::kind)
                .doesNotContain(LONG_BIAS, SHORT_BIAS);
    }

    @Test
    void aTrendWhereTheCallsGoWrongIsNamedAndOrderedByHowFarOffItIs() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // Flat charts: 30 calls, 24 right. After a rise: 12 calls, 3 right. Overall 27/42 = 64%;
        // after a rise 25% — 39 points under. The same calls also lean LONG (27 of 42 against 18
        // rises, 21 points), which is exactly why the order matters: the bigger gap leads.
        repeat(obs, 12, call(LONG, LONG, PlayerInsights.Trend.FLAT, MORNING));
        repeat(obs, 12, call(SHORT, SHORT, PlayerInsights.Trend.FLAT, MORNING));
        repeat(obs, 3, call(LONG, SHORT, PlayerInsights.Trend.FLAT, MORNING));
        repeat(obs, 3, call(SHORT, LONG, PlayerInsights.Trend.FLAT, MORNING));
        repeat(obs, 3, call(LONG, LONG, PlayerInsights.Trend.RISING, MORNING));
        repeat(obs, 9, call(LONG, SHORT, PlayerInsights.Trend.RISING, MORNING));

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        PlayerInsights.Bucket rising = s.trends().get(PlayerInsights.Trend.RISING);
        assertThat(rising.total()).isEqualTo(12);
        assertThat(rising.correct()).isEqualTo(3);
        assertThat(rising.longCalls()).isEqualTo(12);
        assertThat(s.findings().getFirst().kind()).isEqualTo(WEAK_TREND);
        assertThat(s.findings().getFirst().key()).isEqualTo("RISING");
        assertThat(s.findings().getFirst().gapPoints()).isEqualTo(39);
        assertThat(s.findings().get(1).kind()).isEqualTo(LONG_BIAS);
        assertThat(s.findings().get(1).gapPoints()).isEqualTo(21);
    }

    @Test
    void aBucketUnderTheMinimumIsNotComparedWithAnything() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        repeat(obs, 20, call(LONG, LONG, PlayerInsights.Trend.FLAT, MORNING));
        repeat(obs, 20, call(SHORT, SHORT, PlayerInsights.Trend.FLAT, MORNING));
        // Nine calls after a fall, all wrong: dreadful, and one short of counting.
        repeat(obs, PlayerInsights.MIN_BUCKET - 1, call(LONG, SHORT, PlayerInsights.Trend.FALLING, MORNING));

        assertThat(PlayerInsights.fold(obs, VN).findings()).extracting(PlayerInsights.Finding::kind)
                .doesNotContain(WEAK_TREND);
    }

    @Test
    void sessionsAreThePlayersLocalHoursNotUtc() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // Morning in Vietnam: 30 calls, all right. Evening: 12, all wrong.
        repeat(obs, 15, call(LONG, LONG, null, MORNING));
        repeat(obs, 15, call(SHORT, SHORT, null, MORNING));
        repeat(obs, 6, call(LONG, SHORT, null, EVENING));
        repeat(obs, 6, call(SHORT, LONG, null, EVENING));

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        assertThat(s.sessions().get(PlayerInsights.Session.MORNING).total()).isEqualTo(30);
        assertThat(s.sessions().get(PlayerInsights.Session.EVENING).total()).isEqualTo(12);
        assertThat(s.sessions().get(PlayerInsights.Session.NIGHT).total()).isZero();
        assertThat(s.findings()).anySatisfy(f -> {
            assertThat(f.kind()).isEqualTo(WEAK_SESSION);
            assertThat(f.key()).isEqualTo("EVENING");
        });
    }

    @Test
    void lettingTheClockRunOutOftenIsItsOwnFinding() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        repeat(obs, 20, call(LONG, LONG, null, MORNING));
        repeat(obs, 20, call(SHORT, SHORT, null, MORNING));
        // 10 timeouts in 50 calls: 20%.
        repeat(obs, 10, call(null, LONG, null, MORNING));

        assertThat(PlayerInsights.fold(obs, VN).findings()).anySatisfy(f -> {
            assertThat(f.kind()).isEqualTo(TIMEOUTS);
            assertThat(f.gapPoints()).isEqualTo(20);
        });
    }

    @Test
    void aPlayerWhoMostlyLetsTheClockRunOutIsToldSoEvenWithTooFewAnsweredCallsForAnythingElse() {
        List<PlayerInsights.Observation> obs = new ArrayList<>();
        // The shape found on a real account: 18 answered, 71 timed out.
        repeat(obs, 10, call(LONG, LONG, PlayerInsights.Trend.RISING, MORNING));
        repeat(obs, 8, call(SHORT, LONG, PlayerInsights.Trend.RISING, MORNING));
        repeat(obs, 71, call(null, SHORT, null, MORNING));

        PlayerInsights.Summary s = PlayerInsights.fold(obs, VN);

        assertThat(s.enough()).isFalse();
        assertThat(s.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(TIMEOUTS);
            assertThat(f.gapPoints()).isEqualTo(80);
        });
    }

    @Test
    void aTrendIsAMoveLargerThanOneAverageCandleRangeWhateverThePriceLevel() {
        // Five candles, each spanning 10 in range. Net +12 over the window: rising.
        assertThat(PlayerInsights.trendOf(candles(100, 3, 10))).isEqualTo(PlayerInsights.Trend.RISING);
        // Same shape falling.
        assertThat(PlayerInsights.trendOf(candles(100, -3, 10))).isEqualTo(PlayerInsights.Trend.FALLING);
        // Net +4 against a range of 10: flat.
        assertThat(PlayerInsights.trendOf(candles(100, 1, 10))).isEqualTo(PlayerInsights.Trend.FLAT);
        // The same relative shape at a hundredth of the price judges the same — scale-free.
        assertThat(PlayerInsights.trendOf(candles(1, 0.03, 0.1))).isEqualTo(PlayerInsights.Trend.RISING);
        // Too few candles to judge.
        assertThat(PlayerInsights.trendOf(candles(100, 3, 10).subList(0, 4))).isNull();
    }

    /** Five candles opening at {@code start}, each closing {@code step} above its open, each {@code range} tall. */
    private static List<Candle> candles(double start, double step, double range) {
        List<Candle> out = new ArrayList<>();
        double open = start;
        for (int i = 0; i < PlayerInsights.TREND_CANDLES; i++) {
            double close = open + step;
            double mid = (open + close) / 2;
            out.add(new Candle(null, "1h", Instant.EPOCH.plusSeconds(3600L * i),
                    BigDecimal.valueOf(open), BigDecimal.valueOf(mid + range / 2), BigDecimal.valueOf(mid - range / 2),
                    BigDecimal.valueOf(close), BigDecimal.ONE));
            open = close;
        }
        return out;
    }
}
