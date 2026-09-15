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
import com.example.candles.domain.ChallengeResult;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.ChallengeGuessRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;
import com.example.candles.service.RoundTokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Challenge links end to end: a practice chart finished, its signed result turned into a link, a
 * friend playing the same chart — and the two rules that matter more than the happy path. The
 * advertised score can only be one the server saw, and a challenge's guesses must never reach
 * guess_results, because its creator has seen the answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChallengeFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private ChallengeGuessRepository challengeGuesses;
    @Autowired private CandlesProperties properties;
    @Autowired private RoundTokenService tokens;
    @Autowired private JwtService jwt;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Same fixture PracticeRoundFlowTest explains: alternating 1% candles clear both selection gates. */
    private String seedTradablePair() {
        Asset asset = assets.saveAndFlush(
                new Asset("CHAL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        "Challenge pair", AssetType.CRYPTO));
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<Candle> seeded = new ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(100);
        for (int i = 0; i < 60; i++) {
            boolean up = i % 2 == 0;
            BigDecimal open = price;
            BigDecimal close = up ? open.multiply(BigDecimal.valueOf(1.01)) : open.multiply(BigDecimal.valueOf(0.99));
            BigDecimal high = (up ? close : open).multiply(BigDecimal.valueOf(1.005));
            BigDecimal low = (up ? open : close).multiply(BigDecimal.valueOf(0.995));
            seeded.add(new Candle(asset, properties.timeframe(), start.plus(i, ChronoUnit.HOURS),
                    open, high, low, close, BigDecimal.valueOf(1000)));
            price = close;
        }
        candles.saveAllAndFlush(seeded);
        return asset.getSymbol();
    }

    private static void think() throws InterruptedException {
        Thread.sleep(350);
    }

    private User player(String name) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), name);
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.createAccessToken(user);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult post(String url, String body, String bearer) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode getJson(String url, String bearer) throws Exception {
        var request = get(url);
        if (bearer != null) request = request.header("Authorization", bearer);
        return json(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static String guessBody(String token, String direction) {
        return "{\"roundToken\":\"" + token + "\",\"direction\":\"" + direction + "\"}";
    }

    /** Plays a practice chart through, always calling LONG, and returns the last response. */
    private JsonNode finishPracticeChart(String pair, String bearer) throws Exception {
        String token = getJson("/api/practice/round?asset=" + pair, null).path("roundToken").asString();
        JsonNode last = null;
        for (int i = 0; i < properties.round().guessesPerChart(); i++) {
            think();
            last = json(post("/api/practice/guess", guessBody(token, "LONG"), bearer));
            token = last.path("nextRoundToken").asString(null);
        }
        return last;
    }

    /** Plays a challenge through as {@code bearer}, always calling LONG; returns the correct count. */
    private int playChallenge(String id, String bearer) throws Exception {
        JsonNode round = getJson("/api/challenges/" + id, bearer);
        String token = round.path("roundToken").asString();
        int correct = 0;
        for (int i = round.path("guessesMade").asInt(); i < properties.round().guessesPerChart(); i++) {
            think();
            MvcResult result = post("/api/challenges/" + id + "/guess", guessBody(token, "LONG"), bearer);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = json(result);
            if (body.path("correct").asBoolean()) correct++;
            token = body.path("nextRoundToken").asString(null);
        }
        return correct;
    }

    @Test
    void aFinishedPracticeChartHandsBackASignedResultAndOnlyAtTheEnd() throws Exception {
        String pair = seedTradablePair();
        String token = getJson("/api/practice/round?asset=" + pair, null).path("roundToken").asString();
        think();
        JsonNode first = json(post("/api/practice/guess", guessBody(token, "LONG"), null));
        assertThat(first.path("challengeToken").isNull()).isTrue();

        JsonNode last = finishPracticeChart(pair, null);
        assertThat(last.path("sessionComplete").asBoolean()).isTrue();
        ChallengeResult result = tokens.verifyChallengeResult(last.path("challengeToken").asString());
        assertThat(result.total()).isEqualTo(properties.round().guessesPerChart());
        assertThat(result.correct()).isBetween(0, result.total());
    }

    @Test
    void aFriendPlaysTheSameChartAndTheirGuessesStayOutOfGuessResults() throws Exception {
        String pair = seedTradablePair();
        User creator = player("Creator");
        JsonNode finished = finishPracticeChart(pair, bearer(creator));
        int creatorCorrect = tokens.verifyChallengeResult(finished.path("challengeToken").asString()).correct();

        JsonNode created = json(post("/api/challenges",
                "{\"challengeToken\":\"" + finished.path("challengeToken").asString() + "\"}", bearer(creator)));
        String id = created.path("id").asString();
        assertThat(created.path("path").asString()).isEqualTo("/?thach=" + id);
        assertThat(created.path("correct").asInt()).isEqualTo(creatorCorrect);

        // Asking again for the same chart returns the same link rather than a second one.
        JsonNode again = json(post("/api/challenges",
                "{\"challengeToken\":\"" + finished.path("challengeToken").asString() + "\"}", bearer(creator)));
        assertThat(again.path("id").asString()).isEqualTo(id);

        User friend = player("Friend");
        long practiceRowsBefore = guessResults.count();
        JsonNode round = getJson("/api/challenges/" + id, bearer(friend));
        assertThat(round.path("creatorName").asString()).isEqualTo("Creator");
        assertThat(round.path("creatorCorrect").asInt()).isEqualTo(creatorCorrect);
        assertThat(round.path("mine").asBoolean()).isFalse();
        assertThat(round.path("candles").size()).isEqualTo(properties.round().visibleCandles());

        // Same chart: every guess the friend makes lands on the same answers the creator's did.
        int friendCorrect = playChallenge(id, bearer(friend));
        assertThat(friendCorrect).isEqualTo(creatorCorrect);

        assertThat(guessResults.count()).isEqualTo(practiceRowsBefore);
        assertThat(challengeGuesses.findByChallengeIdAndUserIdOrderByGuessNumber(id, friend.getId()))
                .hasSize(properties.round().guessesPerChart());

        JsonNode after = getJson("/api/challenges/" + id, bearer(friend));
        assertThat(after.path("completed").asBoolean()).isTrue();
        assertThat(after.path("roundToken").isNull()).isTrue();
        assertThat(after.path("finishers").size()).isEqualTo(1);
        assertThat(after.path("finishers").get(0).path("displayName").asString()).isEqualTo("Friend");
        assertThat(after.path("finishers").get(0).path("you").asBoolean()).isTrue();
    }

    @Test
    void theCreatorSeesResultsButCannotPlayTheirOwnChallenge() throws Exception {
        String pair = seedTradablePair();
        User creator = player("Creator");
        JsonNode finished = finishPracticeChart(pair, bearer(creator));
        String id = json(post("/api/challenges",
                "{\"challengeToken\":\"" + finished.path("challengeToken").asString() + "\"}", bearer(creator)))
                .path("id").asString();

        JsonNode mine = getJson("/api/challenges/" + id, bearer(creator));
        assertThat(mine.path("mine").asBoolean()).isTrue();
        assertThat(mine.path("completed").asBoolean()).isTrue();
        assertThat(mine.path("roundToken").isNull()).isTrue();

        // A token obtained signed out cannot then be spent signed in as the creator.
        String anonymousToken = getJson("/api/challenges/" + id, null).path("roundToken").asString();
        think();
        assertThat(post("/api/challenges/" + id + "/guess", guessBody(anonymousToken, "LONG"), bearer(creator))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void tokensCannotCrossBetweenPracticeAndChallengesOrBetweenChallenges() throws Exception {
        String pairA = seedTradablePair();
        String pairB = seedTradablePair();
        String idA = json(post("/api/challenges", "{\"challengeToken\":\""
                + finishPracticeChart(pairA, null).path("challengeToken").asString() + "\"}", null)).path("id").asString();
        String idB = json(post("/api/challenges", "{\"challengeToken\":\""
                + finishPracticeChart(pairB, null).path("challengeToken").asString() + "\"}", null)).path("id").asString();

        String challengeToken = getJson("/api/challenges/" + idA, null).path("roundToken").asString();
        String practiceToken = getJson("/api/practice/round?asset=" + pairA, null).path("roundToken").asString();
        think();

        assertThat(post("/api/practice/guess", guessBody(challengeToken, "LONG"), null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(post("/api/challenges/" + idA + "/guess", guessBody(practiceToken, "LONG"), null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(post("/api/challenges/" + idB + "/guess", guessBody(challengeToken, "LONG"), null).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void aRoundTokenIsNotAResultAndAForgedScoreIsRefused() throws Exception {
        String pair = seedTradablePair();
        String roundToken = getJson("/api/practice/round?asset=" + pair, null).path("roundToken").asString();
        assertThat(post("/api/challenges", "{\"challengeToken\":\"" + roundToken + "\"}", null)
                .getResponse().getStatus()).isEqualTo(400);

        String real = finishPracticeChart(pair, null).path("challengeToken").asString();
        String[] parts = real.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThat(post("/api/challenges", "{\"challengeToken\":\"" + forged + "\"}", null)
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(mockMvc.perform(get("/api/challenges/khongcoday")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
    }
}
