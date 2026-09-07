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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Replaying a past daily round, and the one rule the whole feature stands on: it must not be
 * able to stand in for turning up today.
 *
 * A daily streak that could be held by working through the archive is not a streak. That is the
 * property {@code archivePlayDoesNotKeepTheDailyStreakAlive} exists for, and it is the reason
 * archived rounds are recorded under their own mode rather than as daily ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DailyArchiveFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Enough history that any day in the archive window has a window to land in. */
    private void seedTradablePair() {
        Asset asset = assets.saveAndFlush(
                new Asset("ARCH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        "Archive pair", AssetType.CRYPTO));
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

    private static void think() throws InterruptedException {
        Thread.sleep(350);
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "A");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private static LocalDate yesterday() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(1);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode archiveRound(LocalDate day, String bearer) throws Exception {
        var request = get("/api/daily/archive/" + day);
        if (bearer != null) request = request.header("Authorization", bearer);
        return json(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private MvcResult archiveGuess(LocalDate day, String token, String bearer) throws Exception {
        var request = post("/api/daily/archive/" + day + "/guess")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roundToken\":\"" + token + "\",\"direction\":\"LONG\"}");
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void theArchiveListsPastDaysNewestFirstAndNeverToday() throws Exception {
        seedTradablePair();
        JsonNode list = json(mockMvc.perform(get("/api/daily/archive?days=5"))
                .andExpect(status().isOk()).andReturn());

        assertThat(list.size()).isEqualTo(5);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(list.get(0).path("day").asString()).isEqualTo(yesterday().toString());
        for (JsonNode row : list) {
            assertThat(LocalDate.parse(row.path("day").asString())).isBefore(today);
            assertThat(row.path("roundNumber").asLong()).isPositive();
        }
    }

    @Test
    void todayCannotBePlayedThroughTheArchiveDoor() throws Exception {
        seedTradablePair();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Otherwise the daily could be answered where the streak query cannot see the rows.
        mockMvc.perform(get("/api/daily/archive/" + today)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/daily/archive/" + today.plusDays(1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPastDayReplaysAsItsOwnRoundWithNoStreakAndNoNextTime() throws Exception {
        seedTradablePair();
        JsonNode round = archiveRound(yesterday(), "Bearer " + jwt.createAccessToken(player()));

        assertThat(round.path("day").asString()).isEqualTo(yesterday().toString());
        assertThat(round.path("candles").size()).isEqualTo(properties.round().visibleCandles());
        assertThat(round.path("roundToken").asString()).isNotBlank();
        // A replay moves neither, so claiming either would be a lie the client would draw.
        assertThat(round.path("streak").isNull()).isTrue();
        assertThat(round.path("nextRoundAt").isNull()).isTrue();
    }

    /**
     * The rule the feature stands on. Playing yesterday's chart today must leave the daily
     * streak exactly where it was — otherwise a streak is just a measure of how much archive
     * is left, and every badge and share card built on it means nothing.
     */
    @Test
    void archivePlayDoesNotKeepTheDailyStreakAlive() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        assertThat(guessResults.distinctDailyDaysDesc(player.getId())).isEmpty();

        JsonNode round = archiveRound(yesterday(), bearer);
        think();
        assertThat(archiveGuess(yesterday(), round.path("roundToken").asString(), bearer)
                .getResponse().getStatus()).isEqualTo(200);

        // The row exists and is the player's, but it is not a daily one.
        assertThat(guessResults.findAll().stream()
                .filter(g -> g.getUser().getId().equals(player.getId())))
                .isNotEmpty()
                .allMatch(g -> g.getMode() == GuessMode.ARCHIVE);
        assertThat(guessResults.distinctDailyDaysDesc(player.getId())).isEmpty();
    }

    @Test
    void anArchivedDayIsPlayableOnceAndThenReadsAsFinished() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        LocalDate day = yesterday();

        String token = archiveRound(day, bearer).path("roundToken").asString();
        think();
        assertThat(archiveGuess(day, token, bearer).getResponse().getStatus()).isEqualTo(200);
        // The same token again must not spend a second guess.
        assertThat(archiveGuess(day, token, bearer).getResponse().getStatus()).isEqualTo(200);

        JsonNode resumed = archiveRound(day, bearer);
        assertThat(resumed.path("guessesMade").asInt()).isEqualTo(1);
        assertThat(resumed.path("answers").size()).isEqualTo(1);
    }

    /**
     * The day is in the path, not the token, so the two have to be checked against each other.
     * Without that a token minted for one archived day could be spent on another.
     */
    @Test
    void anArchiveTokenCannotBeSpentOnADifferentDay() throws Exception {
        seedTradablePair();
        String bearer = "Bearer " + jwt.createAccessToken(player());
        LocalDate day = yesterday();

        String token = archiveRound(day, bearer).path("roundToken").asString();
        think();

        assertThat(archiveGuess(day.minusDays(1), token, bearer).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void aDailyTokenCannotBeSpentOnTheArchiveOrViceVersa() throws Exception {
        seedTradablePair();
        String bearer = "Bearer " + jwt.createAccessToken(player());

        JsonNode today = json(mockMvc.perform(get("/api/daily/round").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn());
        think();
        assertThat(archiveGuess(yesterday(), today.path("roundToken").asString(), bearer)
                .getResponse().getStatus()).isEqualTo(400);

        String archiveToken = archiveRound(yesterday(), bearer).path("roundToken").asString();
        think();
        assertThat(mockMvc.perform(post("/api/daily/guess")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roundToken\":\"" + archiveToken + "\",\"direction\":\"LONG\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theListReportsWhatThePlayerAlreadyDidOnEachDay() throws Exception {
        seedTradablePair();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        LocalDate day = yesterday();

        String token = archiveRound(day, bearer).path("roundToken").asString();
        think();
        archiveGuess(day, token, bearer);

        JsonNode list = json(mockMvc.perform(get("/api/daily/archive?days=3")
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn());

        JsonNode row = list.get(0);
        assertThat(row.path("day").asString()).isEqualTo(day.toString());
        assertThat(row.path("guessesMade").asInt()).isEqualTo(1);
        assertThat(row.path("completed").asBoolean()).isFalse();
        assertThat(row.path("totalGuesses").asInt()).isEqualTo(properties.round().guessesPerChart());
    }
}
