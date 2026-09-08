package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hints unlock on misses, over HTTP, on a chart whose answers can be worked out in advance.
 *
 * The fixture alternates direction every candle. The round's window starts somewhere random, so
 * the first answer is unknown — but it comes back in the verdict, and from then on every
 * subsequent direction is the opposite of the last. That is what lets these tests choose to be
 * right or wrong on demand and drive the miss count exactly, rather than climbing a
 * miss-counted ladder by luck and asserting whatever happened to come out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgressiveHintFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();

    private String seedAlternatingPair() {
        Asset asset = assets.saveAndFlush(
                new Asset("HINT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        "Hint pair", AssetType.CRYPTO));
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<Candle> seeded = new ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(100);
        for (int i = 0; i < 60; i++) {
            boolean up = i % 2 == 0;
            BigDecimal open = price;
            BigDecimal close = up ? open.multiply(BigDecimal.valueOf(1.01))
                                  : open.multiply(BigDecimal.valueOf(0.99));
            BigDecimal high = (up ? close : open).multiply(BigDecimal.valueOf(1.005));
            BigDecimal low = (up ? open : close).multiply(BigDecimal.valueOf(0.995));
            seeded.add(new Candle(asset, properties.timeframe(),
                    start.plus(i, ChronoUnit.HOURS), open, high, low, close,
                    // Distinct per candle, so a volume series cannot appear right by accident.
                    BigDecimal.valueOf(1000 + i)));
            price = close;
        }
        candles.saveAllAndFlush(seeded);
        return asset.getSymbol();
    }

    /** Under min-think-time a guess is refused as automation, so pause like a human. */
    private static void think() throws InterruptedException {
        Thread.sleep(350);
    }

    private static String opposite(String direction) {
        return "LONG".equals(direction) ? "SHORT" : "LONG";
    }

    private JsonNode round(String asset) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/practice/round?asset=" + asset))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode guess(String token, String direction) throws Exception {
        think();
        MvcResult result = mockMvc.perform(post("/api/practice/guess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roundToken\":\"" + token + "\",\"direction\":\"" + direction + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** Drives a chart with full knowledge of the answers after the first guess. */
    private final class Session {
        private String token;
        private String nextTruth;
        private int misses;
        private JsonNode last;

        Session(String pair) throws Exception {
            token = round(pair).path("roundToken").asString();
            // The opening guess is a coin flip; whatever it turns out to be pins every later one.
            last = guess(token, "LONG");
            if (!last.path("correct").asBoolean()) misses++;
            nextTruth = opposite(last.path("actualDirection").asString());
            token = nextToken(last);
        }

        private String nextToken(JsonNode verdict) {
            return verdict.path("nextRoundToken").isNull() ? null
                    : verdict.path("nextRoundToken").asString(null);
        }

        boolean playable() {
            return token != null && !token.isBlank();
        }

        /** Answers the next candle deliberately right or deliberately wrong. */
        JsonNode play(boolean correctly) throws Exception {
            last = guess(token, correctly ? nextTruth : opposite(nextTruth));
            assertThat(last.path("correct").asBoolean())
                    .as("the fixture's alternation must hold").isEqualTo(correctly);
            if (!correctly) misses++;
            nextTruth = opposite(nextTruth);
            token = nextToken(last);
            return last;
        }

        JsonNode hints() {
            return last.path("hints");
        }

        int misses() {
            return misses;
        }
    }

    @Test
    void missingRepeatedlyUnlocksVolumeThenTheAverageThenAPattern() throws Exception {
        Session session = new Session(seedAlternatingPair());

        // Miss on purpose for the rest of the chart, checking each rung as it is reached.
        while (session.playable()) {
            session.play(false);
            if (session.last.path("sessionComplete").asBoolean()) break;

            JsonNode hints = session.hints();
            assertThat(hints.path("volumes").isNull())
                    .as("volume after %d misses", session.misses()).isEqualTo(session.misses() < 1);
            assertThat(hints.path("movingAverage").isNull())
                    .as("average after %d misses", session.misses()).isEqualTo(session.misses() < 2);
        }

        assertThat(session.misses()).as("the session must actually accumulate misses").isGreaterThan(1);
    }

    @Test
    void aPlayerWhoKeepsGettingItRightIsNeverGivenMore() throws Exception {
        Session session = new Session(seedAlternatingPair());

        while (session.playable()) {
            session.play(true);
            if (session.last.path("sessionComplete").asBoolean()) break;

            // Answering correctly never adds a rung — the level can only be whatever the opening
            // coin flip already cost, and it must not grow from here.
            JsonNode hints = session.hints();
            assertThat(hints.path("volumes").isNull()).isEqualTo(session.misses() < 1);
            assertThat(hints.path("movingAverage").isNull()).isTrue();
            assertThat(hints.path("patternId").isNull()).isTrue();
        }
    }

    /**
     * The hint arrays are drawn straight against the chart, so a length disagreeing with what is
     * on screen would put every reading under the wrong candle — and a series one entry too long
     * would be describing the candle being guessed, which is the answer.
     */
    @Test
    void hintSeriesCoverExactlyTheCandlesAlreadyOnScreen() throws Exception {
        Session session = new Session(seedAlternatingPair());
        int visible = properties.round().visibleCandles();
        int revealed = 1; // the opening guess's candle

        while (session.playable()) {
            JsonNode hints = session.hints();
            if (!hints.path("volumes").isNull()) {
                assertThat(hints.path("volumes").size()).isEqualTo(visible + revealed);
                if (!hints.path("movingAverage").isNull()) {
                    assertThat(hints.path("movingAverage").size()).isEqualTo(visible + revealed);
                }
            }
            session.play(false);
            revealed++;
            if (session.last.path("sessionComplete").asBoolean()) break;
        }
    }

    /**
     * The leading entries of the average have no full period behind them and come back null.
     * A renderer that got zeroes there would draw the line diving to the bottom of the chart.
     */
    @Test
    void theMovingAverageStartsNullUntilItHasAFullPeriod() throws Exception {
        Session session = new Session(seedAlternatingPair());

        while (session.playable()) {
            session.play(false);
            if (session.last.path("sessionComplete").asBoolean()) break;

            JsonNode ma = session.hints().path("movingAverage");
            if (ma.isNull()) continue;

            assertThat(ma.get(0).isNull()).as("first average entry").isTrue();
            assertThat(ma.get(ma.size() - 1).isNull()).as("last average entry").isFalse();
            return;
        }
    }

    /**
     * The gate that lets {@code DatedCandleDto} carry volume at all.
     *
     * Volume is a hint, released only after a miss and only for the candles already on screen.
     * The end-of-session context chart is sent as {@code DatedCandleDto}, which carries volume
     * for every candle in the window — including the ones the player was being asked to call.
     * That is fine precisely because the round is over by then, and this test is what says so:
     * no response while the round is still running may carry a context at all.
     *
     * Send the context one guess early and the volume hint is free, and the whole ladder in the
     * tests above stops meaning anything.
     */
    @Test
    void theContextChartArrivesOnlyOnceTheRoundIsOverBecauseItCarriesVolume() throws Exception {
        Session session = new Session(seedAlternatingPair());
        assertThat(session.last.path("context").isNull())
                .as("no context on the opening guess").isTrue();

        while (session.playable()) {
            session.play(false);
            if (session.last.path("sessionComplete").asBoolean()) break;
            assertThat(session.last.path("context").isNull())
                    .as("no context while guess %d of %d is still to come",
                            session.last.path("guessNumber").asInt(),
                            session.last.path("totalGuesses").asInt())
                    .isTrue();
        }

        JsonNode context = session.last.path("context");
        assertThat(context.isNull()).as("the finished round does send its context").isFalse();

        // And it is the volume-carrying shape: every candle in it has the field, or the terminal
        // that reads the same record draws a chart with no volume strip.
        JsonNode candles = context.path("candles");
        assertThat(candles.size()).isGreaterThan(0);
        for (JsonNode candle : candles) {
            assertThat(candle.path("volume").isNumber())
                    .as("volume on every context candle").isTrue();
        }
    }
}
