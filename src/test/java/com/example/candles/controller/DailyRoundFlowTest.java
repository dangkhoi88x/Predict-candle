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
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily challenge over HTTP: everybody gets one chart, and a player gets one go at it.
 *
 * Nothing records that an attempt happened — the guesses are the attempt — so the rules these
 * tests are really checking are that reading the round back reconstructs where a player got to,
 * and that the recorded rows make a second attempt impossible rather than merely hidden.
 *
 * A pair is seeded here for the same reason {@code PracticeRoundFlowTest} seeds one: CI starts
 * on an empty database and cannot reach Binance, so a test that assumed the backfill had run
 * would be asserting that someone had already started the app. The daily round walks on from
 * its seeded asset choice to the first pair with history behind it, which is what lets this
 * work with only one playable pair present.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DailyRoundFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Enough history that the day's window has somewhere to land, on an empty database too. */
    private void seedTradablePair() {
        Asset asset = assets.saveAndFlush(
                new Asset("TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        "Test pair", AssetType.CRYPTO));
        Instant start = Instant.parse("2020-01-01T00:00:00Z");
        List<Candle> seeded = new ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(100);
        for (int i = 0; i < 400; i++) {
            boolean up = i % 2 == 0;
            BigDecimal open = price;
            BigDecimal close = up ? open.multiply(BigDecimal.valueOf(1.01))
                                  : open.multiply(BigDecimal.valueOf(0.99));
            BigDecimal high = (up ? close : open).multiply(BigDecimal.valueOf(1.005));
            BigDecimal low = (up ? open : close).multiply(BigDecimal.valueOf(0.995));
            seeded.add(new Candle(asset, properties.timeframe(),
                    start.plus(i, ChronoUnit.HOURS), open, high, low, close,
                    BigDecimal.valueOf(1000)));
            price = close;
        }
        candles.saveAllAndFlush(seeded);
    }

    /** Under min-think-time (250ms) a guess is refused as automation, so pause like a human. */
    private static void think() throws InterruptedException {
        Thread.sleep(350);
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "D");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private JsonNode round(String bearer) throws Exception {
        var request = get("/api/daily/round");
        if (bearer != null) request = request.header("Authorization", bearer);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult guess(String path, String token, String direction, String bearer) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roundToken\":\"" + token + "\",\"direction\":\"" + direction + "\"}");
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    /** Plays the whole chart through, returning the last verdict. */
    private JsonNode playToCompletion(String firstToken, String bearer) throws Exception {
        String token = firstToken;
        JsonNode verdict = null;
        while (token != null && !token.isBlank()) {
            think();
            MvcResult result = guess("/api/daily/guess", token, "LONG", bearer);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            verdict = mapper.readTree(result.getResponse().getContentAsString());
            token = verdict.path("nextRoundToken").isNull() ? null
                    : verdict.path("nextRoundToken").asString(null);
        }
        return verdict;
    }

    @Test
    void everyoneAsksAndGetsTheSameChartWithTodaysNumberOnIt() throws Exception {
        seedTradablePair();

        JsonNode first = round(null);
        JsonNode second = round("Bearer " + jwt.createAccessToken(player()));

        assertThat(first.path("asset").asString()).isEqualTo(second.path("asset").asString());
        assertThat(first.path("day").asString()).isEqualTo(second.path("day").asString());
        assertThat(first.path("roundNumber").asLong()).isEqualTo(second.path("roundNumber").asLong());
        assertThat(first.path("roundNumber").asLong()).isPositive();
        assertThat(first.path("candles").size()).isEqualTo(properties.round().visibleCandles());
        // The answer stays inside the signed token, exactly as in practice.
        assertThat(first.toString()).doesNotContain("actualDirection");
    }

    @Test
    void aSignedOutVisitorGetsAPlayableChartButNoRecordedState() throws Exception {
        seedTradablePair();

        JsonNode round = round(null);

        assertThat(round.path("roundToken").asString()).isNotBlank();
        assertThat(round.path("guessesMade").asInt()).isZero();
        assertThat(round.path("completed").asBoolean()).isFalse();
        assertThat(round.path("streak").isNull()).isTrue();
    }

    @Test
    void aHalfPlayedRoundIsPickedUpWhereItWasLeft() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        JsonNode round = round(bearer);
        think();
        assertThat(guess("/api/daily/guess", round.path("roundToken").asString(), "LONG", bearer)
                .getResponse().getStatus()).isEqualTo(200);

        // Closing the tab and coming back must not restart the day or cost the attempt.
        JsonNode resumed = round(bearer);
        assertThat(resumed.path("guessesMade").asInt()).isEqualTo(1);
        assertThat(resumed.path("completed").asBoolean()).isFalse();
        assertThat(resumed.path("roundToken").asString()).isNotBlank();
        assertThat(resumed.path("answers").size()).isEqualTo(1);
    }

    @Test
    void finishingTodayClosesItAndHandsBackTheAnswers() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        playToCompletion(round(bearer).path("roundToken").asString(), bearer);

        JsonNode after = round(bearer);
        assertThat(after.path("completed").asBoolean()).isTrue();
        assertThat(after.path("guessesMade").asInt()).isEqualTo(properties.round().guessesPerChart());
        // No token: there is nothing left to play until tomorrow.
        assertThat(after.path("roundToken").isNull()).isTrue();
        assertThat(after.path("answers").size()).isEqualTo(properties.round().guessesPerChart());
        // The answer candles only arrive once they can no longer help.
        assertThat(after.path("resolvedCandles").size()).isEqualTo(properties.round().guessesPerChart());
        assertThat(after.path("nextRoundAt").asString()).isNotBlank();
        assertThat(after.path("streak").path("current").asInt()).isEqualTo(1);
    }

    /**
     * The exploit the mode claim exists to close. Without it a player could take the daily
     * token, spend it on the practice endpoint to read the answer for free — practice rows do
     * not count as a daily attempt — and then play the daily already knowing it.
     */
    @Test
    void aDailyTokenCannotBeSpentOnPracticeAndViceVersa() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        String dailyToken = round(bearer).path("roundToken").asString();
        think();
        assertThat(guess("/api/practice/guess", dailyToken, "LONG", bearer)
                .getResponse().getStatus()).isEqualTo(400);

        JsonNode practice = mapper.readTree(mockMvc
                .perform(get("/api/practice/round?asset=" + assets.findAll().getFirst().getSymbol()))
                .andReturn().getResponse().getContentAsString());
        think();
        assertThat(guess("/api/daily/guess", practice.path("roundToken").asString(), "LONG", bearer)
                .getResponse().getStatus()).isEqualTo(400);

        // And the refused attempts left the day untouched.
        assertThat(round(bearer).path("guessesMade").asInt()).isZero();
    }

    @Test
    void replayingADailyTokenDoesNotSpendASecondGuess() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        String token = round(bearer).path("roundToken").asString();
        think();
        assertThat(guess("/api/daily/guess", token, "LONG", bearer).getResponse().getStatus()).isEqualTo(200);
        assertThat(guess("/api/daily/guess", token, "SHORT", bearer).getResponse().getStatus()).isEqualTo(200);

        assertThat(round(bearer).path("guessesMade").asInt()).isEqualTo(1);
    }

    @Test
    void dailyGuessesAreRecordedAsDailyRatherThanPractice() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        String token = round(bearer).path("roundToken").asString();
        think();
        assertThat(guess("/api/daily/guess", token, "LONG", bearer)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(guessResults.findAll().stream()
                .filter(g -> g.getUser().getId().equals(player.getId())))
                .isNotEmpty()
                .allMatch(g -> g.getMode() == GuessMode.DAILY);
    }
}
