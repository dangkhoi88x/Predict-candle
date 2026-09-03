package com.example.candles.api;

import com.example.candles.auth.JwtService;
import com.example.candles.domain.Role;
import com.example.candles.domain.User;
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
    @Autowired private JwtService jwt;

    private final ObjectMapper mapper = new ObjectMapper();

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
        JsonNode round = round("BTCUSDT");

        assertThat(round.path("asset").asString()).isEqualTo("BTCUSDT");
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
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        long before = guessResults.resultFlagsInPlayOrder(player.getId()).size();

        JsonNode round = round("BTCUSDT");
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
        long before = guessResults.count();

        JsonNode round = round("BTCUSDT");
        think();
        MvcResult result = guess(round.path("roundToken").asString(), "SHORT", null);

        // Playing without an account is supported and stays anonymous — nothing to attribute.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(guessResults.count()).isEqualTo(before);
    }

    @Test
    void aTimeoutIsRecordedAsAnAnswerlessGuessRatherThanAWrongOne() throws Exception {
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        JsonNode round = round("BTCUSDT");
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
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        JsonNode round = round("BTCUSDT");
        String token = round.path("roundToken").asString();
        think();

        assertThat(guess(token, "LONG", bearer).getResponse().getStatus()).isEqualTo(200);
        assertThat(guess(token, "LONG", bearer).getResponse().getStatus()).isEqualTo(200);

        assertThat(guessResults.resultFlagsInPlayOrder(player.getId())).hasSize(1);
    }

    @Test
    void aTamperedOrInventedTokenIsRefused() throws Exception {
        JsonNode round = round("BTCUSDT");
        String token = round.path("roundToken").asString();
        think();

        // Same payload, one character of the signature changed.
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
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
        for (int i = 0; i < 8; i++) {
            JsonNode round = round("BTCUSDT");
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
