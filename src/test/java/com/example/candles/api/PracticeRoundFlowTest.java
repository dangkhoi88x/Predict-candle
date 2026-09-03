package com.example.candles.api;

import com.example.candles.auth.JwtService;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.AssetType;
import com.example.candles.domain.Candle;
import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The round loop over HTTP: ask for a chart, answer it, be scored.
 *
 * This is the whole product, and it had no test at all — every one of the seventy-odd covered
 * the admin surface instead. The gap is not theoretical: a read path with the same lack of
 * coverage returned 500 for four days without anyone noticing, and it was the profile page.
 *
 * The round's answer never leaves the server in the clear. It lives inside the signed
 * roundToken, so these tests play the way a browser does — take the token they are handed, send
 * it back, and read the verdict.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PracticeRoundFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * A pair of this test's own, with history of this test's own.
     *
     * Playing BTCUSDT instead passed here and failed on CI, and the reason is worth keeping:
     * a developer machine has ~40k candles per pair from the first-run Binance backfill, while
     * CI starts on an empty database and cannot reach Binance at all. The round endpoint needs
     * `visible + guesses + reveal` candles to exist before it can deal anything, so the suite
     * was quietly asserting that someone had already run the app.
     *
     * Seeding here also makes the fixture legible: 60 candles alternating direction with a 1%
     * body inside a 1.5% range clear both gates the selector applies — answers decisive enough
     * to have a direction, and a window not so flat it is a coin flip.
     */
    private String seedTradablePair() {
        Asset asset = assets.saveAndFlush(
                new Asset("TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        "Test pair", AssetType.CRYPTO));
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
                    BigDecimal.valueOf(1000)));
            price = close;
        }
        candles.saveAllAndFlush(seeded);
        return asset.getSymbol();
    }

    /** Under min-think-time (250ms) a guess is refused as automation, so pause like a human. */
    private static void think() throws InterruptedException {
        Thread.sleep(350);
    }

    private JsonNode round(String asset) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/practice/round?asset=" + asset))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult guess(String token, String direction, String bearer) throws Exception {
        String body = direction == null
                ? "{\"roundToken\":\"" + token + "\"}"
                : "{\"roundToken\":\"" + token + "\",\"direction\":\"" + direction + "\"}";
        var request = post("/api/practice/guess").contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "P");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    @Test
    void aRoundArrivesWithCandlesAndASignedTokenButNeverTheAnswer() throws Exception {
        String pair = seedTradablePair();
        JsonNode round = round(pair);

        assertThat(round.path("asset").asString()).isEqualTo(pair);
        assertThat(round.path("candles").size()).isEqualTo(20);
        assertThat(round.path("guessSeconds").asInt()).isPositive();

        String token = round.path("roundToken").asString();
        assertThat(token).isNotBlank();
        /* Three segments and a signature. The answer is inside it, which is only safe because
           the client cannot read a claim it cannot verify — and cannot forge one either. */
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(round.toString()).doesNotContain("actualDirection").doesNotContain("answer");
    }

    @Test
    void answeringScoresTheGuessAndRecordsItForASignedInPlayer() throws Exception {
        String pair = seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        long before = guessResults.resultFlagsInPlayOrder(player.getId()).size();

        JsonNode round = round(pair);
        think();
        MvcResult result = guess(round.path("roundToken").asString(), "LONG", bearer);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode verdict = mapper.readTree(result.getResponse().getContentAsString());
        // The verdict names the truth, so a player can see why they were wrong.
        assertThat(verdict.path("actualDirection").asString()).isIn("LONG", "SHORT");
        assertThat(verdict.path("correct").isBoolean()).isTrue();
        assertThat(verdict.path("correct").asBoolean())
                .isEqualTo("LONG".equals(verdict.path("actualDirection").asString()));

        assertThat(guessResults.resultFlagsInPlayOrder(player.getId()).size()).isEqualTo(before + 1);
    }

    @Test
    void anonymousPlayIsScoredButNeverRecorded() throws Exception {
        String pair = seedTradablePair();
        long before = guessResults.count();

        JsonNode round = round(pair);
        think();
        MvcResult result = guess(round.path("roundToken").asString(), "SHORT", null);

        // Playing without an account is supported and stays anonymous — nothing to attribute.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(guessResults.count()).isEqualTo(before);
    }

    @Test
    void aTimeoutIsRecordedAsAnAnswerlessGuessRatherThanAWrongOne() throws Exception {
        String pair = seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        JsonNode round = round(pair);
        think();
        // No direction: the client reporting that its countdown expired.
        MvcResult result = guess(round.path("roundToken").asString(), null, bearer);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // It still counts as a guess against the player, which is why the row exists at all.
        assertThat(guessResults.resultFlagsInPlayOrder(player.getId())).hasSize(1);
        assertThat(guessResults.findAll().stream()
                .filter(g -> g.getUser().getId().equals(player.getId()))
                .allMatch(g -> g.getGuessedDirection() == null)).isTrue();
    }

    /**
     * The unique constraint on (user, asset, timeframe, startIndex, guessNumber) is the only
     * thing stopping a captured token being replayed for points. It has been in the schema
     * since V1 and nothing has ever demonstrated that it works.
     */
    @Test
    void replayingTheSameTokenDoesNotScoreTwice() throws Exception {
        String pair = seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        JsonNode round = round(pair);
        String token = round.path("roundToken").asString();
        think();

        assertThat(guess(token, "LONG", bearer).getResponse().getStatus()).isEqualTo(200);
        assertThat(guess(token, "LONG", bearer).getResponse().getStatus()).isEqualTo(200);

        assertThat(guessResults.resultFlagsInPlayOrder(player.getId())).hasSize(1);
    }

    @Test
    void aTamperedOrInventedTokenIsRefused() throws Exception {
        String pair = seedTradablePair();
        JsonNode round = round(pair);
        String token = round.path("roundToken").asString();
        think();

        /* Same payload, one character of the signature changed — and it has to be a
           character in the middle. An HS256 signature is 32 bytes, which is 43 unpadded
           base64url characters carrying 258 bits, so the last character's low two bits
           decode to nothing: flipping it between neighbouring values yields the same bytes
           and a signature that still verifies. Tampering with the tail passed by luck. */
        String[] parts = token.split("\\.");
        char[] signature = parts[2].toCharArray();
        signature[0] = signature[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);
        assertThat(guess(tampered, "LONG", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(guess("not.a.token", "LONG", null).getResponse().getStatus()).isEqualTo(400);
    }

    /**
     * The floor that separates a person from a script. It used to hold about one time in three:
     * timing was measured from the JWT's `iat`, which carries whole seconds, so a token minted
     * at .900 read as .000 and an instant answer looked most of a second old. Repeated here
     * because a single run of a check that used to pass by luck proves nothing.
     */
    @Test
    void answeringFasterThanAPersonCouldIsRefusedEveryTime() throws Exception {
        String pair = seedTradablePair();
        for (int i = 0; i < 8; i++) {
            JsonNode round = round(pair);
            int status = guess(round.path("roundToken").asString(), "LONG", null)
                    .getResponse().getStatus();
            // 408, not 400: the request is well-formed, it just arrived outside its window.
            assertThat(status).as("instant answer on attempt %d", i + 1).isEqualTo(408);
            Thread.sleep(130);   // land on a different point within the second each time
        }
    }

    @Test
    void anUnknownOrDisabledPairCannotBePlayed() throws Exception {
        mockMvc.perform(get("/api/practice/round?asset=NOPEUSDT"))
                .andExpect(status().isBadRequest());
    }
}
